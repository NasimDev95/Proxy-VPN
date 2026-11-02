package com.example.tunnel

import android.net.VpnService
import android.util.Base64
import com.example.model.ProxyConfig
import com.example.model.ProxyType
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * TcpRelay manages user-space TCP proxying from the virtual TUN interface to the remote proxy.
 * Key capabilities:
 * - Domain-based CONNECT: uses DnsCache or TLS SNI so HTTPS sites (e.g. aistudio.google.com) load without cert errors.
 * - Ordered Writer: FIFO Channel per session ensures proxy stream integrity.
 * - Deferred SYN-ACK / RST on failure: prevents client hanging if proxy is down.
 * - Sequence validation: drops duplicate/out-of-order packets.
 * - Half-close handling: supports client FIN with socket shutdownOutput() until server EOF.
 * - TCP MSS option (1360): prevents fragmentation over tunnels.
 * - Non-buffering byte-level HTTP CONNECT reading: zero lookahead byte loss for TLS handshakes.
 */
class TcpRelay(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val proxyConfigProvider: () -> ProxyConfig,
    private val tunWriter: (ByteArray) -> Unit
) {

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private var cleanupJob: Job? = null

    init {
        startIdleCleanupLoop()
    }

    fun handleTcpPacket(
        ipPacket: PacketUtils.ParsedIpPacket,
        tcpPacket: PacketUtils.ParsedTcpPacket,
        rawPacket: ByteArray
    ) {
        val sessionKey = "${tcpPacket.sourcePort}-${ipPacket.destAddress.hostAddress}:${tcpPacket.destPort}"
        val existingSession = sessions[sessionKey]

        // 1. SYN packet (new connection or retransmission)
        if (tcpPacket.isSyn) {
            if (existingSession != null && existingSession.state == SessionState.CONNECTED) {
                // Retransmitted SYN for active session: re-send SYN-ACK with MSS 1360 without killing session
                AppLogger.d(TAG, "Retransmitted SYN received for established session $sessionKey")
                val synAck = PacketUtils.buildTcpPacket(
                    srcAddress = existingSession.remoteAddress,
                    dstAddress = existingSession.clientAddress,
                    srcPort = existingSession.remotePort,
                    dstPort = existingSession.clientPort,
                    sequenceNumber = (existingSession.ourSeq - 1) and 0xFFFFFFFFL,
                    ackNumber = existingSession.theirSeq,
                    flags = PacketUtils.TCP_FLAG_SYN or PacketUtils.TCP_FLAG_ACK,
                    mss = 1360
                )
                tunWriter(synAck)
                return
            }

            // Clean up any stale closed session
            existingSession?.close()
            sessions.remove(sessionKey)

            handleNewSyn(ipPacket, tcpPacket, sessionKey)
            return
        }

        // 2. No active session found
        if (existingSession == null || existingSession.state == SessionState.CLOSED) {
            if (!tcpPacket.isRst) {
                sendRst(
                    srcAddress = ipPacket.destAddress,
                    dstAddress = ipPacket.sourceAddress,
                    srcPort = tcpPacket.destPort,
                    dstPort = tcpPacket.sourcePort,
                    seq = tcpPacket.ackNumber,
                    ack = (tcpPacket.sequenceNumber + 1) and 0xFFFFFFFFL
                )
            }
            return
        }

        existingSession.markActive()

        // 3. RST packet
        if (tcpPacket.isRst) {
            AppLogger.d(TAG, "RST received from client for $sessionKey")
            closeSession(existingSession)
            return
        }

        // 4. FIN packet (client half-close)
        if (tcpPacket.isFin) {
            handleClientFin(existingSession, tcpPacket)
            return
        }

        // 5. Data payload
        if (tcpPacket.payloadLength > 0) {
            handleClientData(existingSession, tcpPacket, rawPacket)
        }
    }

    private fun handleNewSyn(
        ipPacket: PacketUtils.ParsedIpPacket,
        tcpPacket: PacketUtils.ParsedTcpPacket,
        sessionKey: String
    ) {
        val initialOurSeq = (System.currentTimeMillis() and 0x7FFFFFFFL)
        val theirSeq = (tcpPacket.sequenceNumber + 1) and 0xFFFFFFFFL

        val session = TcpSession(
            sessionKey = sessionKey,
            clientAddress = ipPacket.sourceAddress,
            clientPort = tcpPacket.sourcePort,
            remoteAddress = ipPacket.destAddress,
            remotePort = tcpPacket.destPort,
            ourSeq = initialOurSeq,
            theirSeq = theirSeq
        )
        sessions[sessionKey] = session

        // Check if destination domain is known from DnsCache
        val cachedDomain = DnsCache.get(ipPacket.destAddress)

        if (cachedDomain != null) {
            session.targetHost = cachedDomain
            AppLogger.i(TAG, "DNS Cache hit: ${ipPacket.destAddress.hostAddress} -> $cachedDomain")
            // Establish tunnel to proxy first, then send SYN-ACK upon success
            connectAndAcknowledge(session, cachedDomain)
        } else {
            // Domain not in cache: Send SYN-ACK with MSS 1360 so client sends initial TLS ClientHello
            AppLogger.d(TAG, "No cached domain for ${ipPacket.destAddress.hostAddress}; awaiting ClientHello for SNI")
            sendSynAck(session)
        }
    }

    private fun sendSynAck(session: TcpSession) {
        val synAckPacket = PacketUtils.buildTcpPacket(
            srcAddress = session.remoteAddress,
            dstAddress = session.clientAddress,
            srcPort = session.remotePort,
            dstPort = session.clientPort,
            sequenceNumber = session.ourSeq,
            ackNumber = session.theirSeq,
            flags = PacketUtils.TCP_FLAG_SYN or PacketUtils.TCP_FLAG_ACK,
            mss = 1360
        )
        tunWriter(synAckPacket)
        session.ourSeq = (session.ourSeq + 1) and 0xFFFFFFFFL
    }

    private fun handleClientData(
        session: TcpSession,
        tcpPacket: PacketUtils.ParsedTcpPacket,
        rawPacket: ByteArray
    ) {
        val payloadLength = tcpPacket.payloadLength
        val seq = tcpPacket.sequenceNumber

        // Validate client sequence numbers
        if (seq < session.theirSeq) {
            // Duplicate packet: acknowledge expected sequence and drop duplicate data
            sendAck(session)
            return
        }

        if (seq > session.theirSeq) {
            // Out of order: send ACK for expected sequence to prompt client retransmission
            sendAck(session)
            return
        }

        val payload = ByteArray(payloadLength)
        System.arraycopy(rawPacket, tcpPacket.payloadOffset, payload, 0, payloadLength)

        session.theirSeq = (session.theirSeq + payloadLength) and 0xFFFFFFFFL
        sendAck(session)

        // If proxy connection not yet started (waiting for SNI)
        if (session.state == SessionState.CONNECTING && session.targetHost == null) {
            val sni = SniParser.extractSni(payload, 0, payload.size)
            val targetHost = if (!sni.isNullOrEmpty()) {
                DnsCache.put(session.remoteAddress, sni, 300)
                AppLogger.i(TAG, "SNI parsed: ${session.remoteAddress.hostAddress} -> $sni")
                sni
            } else {
                val ipStr = session.remoteAddress.hostAddress ?: "127.0.0.1"
                AppLogger.w(TAG, "No SNI detected for $ipStr:${session.remotePort}; falling back to IP CONNECT")
                ipStr
            }
            session.targetHost = targetHost

            // Queue initial packet in ordered channel
            session.outboundChannel.trySend(payload)
            launchProxyConnection(session, targetHost)
            return
        }

        // Active connection: write to ordered channel
        session.outboundChannel.trySend(payload)
    }

    private fun sendAck(session: TcpSession) {
        val ackPacket = PacketUtils.buildTcpPacket(
            srcAddress = session.remoteAddress,
            dstAddress = session.clientAddress,
            srcPort = session.remotePort,
            dstPort = session.clientPort,
            sequenceNumber = session.ourSeq,
            ackNumber = session.theirSeq,
            flags = PacketUtils.TCP_FLAG_ACK
        )
        tunWriter(ackPacket)
    }

    private fun handleClientFin(session: TcpSession, tcpPacket: PacketUtils.ParsedTcpPacket) {
        session.theirSeq = (tcpPacket.sequenceNumber + 1) and 0xFFFFFFFFL
        session.state = SessionState.HALF_CLOSED
        sendAck(session)

        scope.launch(Dispatchers.IO) {
            try {
                // Half-close: shutdown outbound writing to proxy, but keep reading until server closes
                session.proxySocket?.shutdownOutput()
                AppLogger.d(TAG, "Client sent FIN for ${session.sessionKey}; half-closed output")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error shutting down output for ${session.sessionKey}: ${e.message}")
                closeSession(session)
            }
        }
    }

    private fun connectAndAcknowledge(session: TcpSession, targetHost: String) {
        scope.launch(Dispatchers.IO) {
            val success = establishProxyConnection(session, targetHost)
            if (success) {
                // Send SYN-ACK now that tunnel is confirmed established
                sendSynAck(session)
            } else {
                sendRst(
                    srcAddress = session.remoteAddress,
                    dstAddress = session.clientAddress,
                    srcPort = session.remotePort,
                    dstPort = session.clientPort,
                    seq = session.ourSeq,
                    ack = session.theirSeq
                )
                closeSession(session)
            }
        }
    }

    private fun launchProxyConnection(session: TcpSession, targetHost: String) {
        scope.launch(Dispatchers.IO) {
            val success = establishProxyConnection(session, targetHost)
            if (!success) {
                sendRst(
                    srcAddress = session.remoteAddress,
                    dstAddress = session.clientAddress,
                    srcPort = session.remotePort,
                    dstPort = session.clientPort,
                    seq = session.ourSeq,
                    ack = session.theirSeq
                )
                closeSession(session)
            }
        }
    }

    private fun establishProxyConnection(session: TcpSession, targetHost: String): Boolean {
        var socket: Socket? = null
        return try {
            val config = proxyConfigProvider()
            socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 10000
            vpnService.protect(socket)

            socket.connect(InetSocketAddress(config.host, config.port), 8000)

            val tunnelOk = when (config.type) {
                ProxyType.HTTP -> establishHttpTunnel(socket, targetHost, session.remotePort, config)
                ProxyType.SOCKS5 -> establishSocks5Tunnel(socket, targetHost, session.remotePort, config)
            }

            if (!tunnelOk || session.state == SessionState.CLOSED) {
                socket.close()
                return false
            }

            // Tunnel is now established: remove 15-second timeout for streaming
            socket.soTimeout = 0
            session.proxySocket = socket
            session.state = SessionState.CONNECTED
            AppLogger.i(TAG, "Tunnel established to $targetHost:${session.remotePort} via proxy")

            // 1. Launch ordered outbound writer loop
            val outStream = socket.getOutputStream()
            session.writerJob = scope.launch(Dispatchers.IO) {
                try {
                    for (chunk in session.outboundChannel) {
                        outStream.write(chunk)
                        outStream.flush()
                        session.markActive()
                    }
                } catch (e: Exception) {
                    if (session.state != SessionState.CLOSED) {
                        AppLogger.d(TAG, "Writer loop ended for ${session.sessionKey}: ${e.message}")
                        closeSession(session)
                    }
                }
            }

            // 2. Launch inbound reader loop (Proxy -> TUN)
            val inStream = socket.getInputStream()
            session.readerJob = scope.launch(Dispatchers.IO) {
                readFromProxyLoop(session, inStream)
            }

            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Proxy connect failed for $targetHost:${session.remotePort}: ${e.message}")
            try { socket?.close() } catch (_: Exception) { }
            false
        }
    }

    private fun readFromProxyLoop(session: TcpSession, inStream: InputStream) {
        val buffer = ByteArray(1360) // Matches MSS 1360
        try {
            while (scope.isActive && session.state != SessionState.CLOSED) {
                val bytesRead = inStream.read(buffer)
                if (bytesRead == -1) {
                    // Server EOF
                    AppLogger.d(TAG, "Server EOF received for ${session.sessionKey}")
                    break
                }

                if (bytesRead > 0) {
                    val chunk = ByteArray(bytesRead)
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead)

                    val dataPacket = PacketUtils.buildTcpPacket(
                        srcAddress = session.remoteAddress,
                        dstAddress = session.clientAddress,
                        srcPort = session.remotePort,
                        dstPort = session.clientPort,
                        sequenceNumber = session.ourSeq,
                        ackNumber = session.theirSeq,
                        flags = PacketUtils.TCP_FLAG_PSH or PacketUtils.TCP_FLAG_ACK,
                        payload = chunk
                    )
                    tunWriter(dataPacket)
                    session.ourSeq = (session.ourSeq + bytesRead) and 0xFFFFFFFFL
                    session.markActive()
                }
            }
        } catch (e: Exception) {
            if (scope.isActive && session.state != SessionState.CLOSED) {
                AppLogger.d(TAG, "Reader loop ended for ${session.sessionKey}: ${e.message}")
            }
        } finally {
            // Complete handshake shutdown with client FIN
            if (session.state != SessionState.CLOSED) {
                val finPacket = PacketUtils.buildTcpPacket(
                    srcAddress = session.remoteAddress,
                    dstAddress = session.clientAddress,
                    srcPort = session.remotePort,
                    dstPort = session.clientPort,
                    sequenceNumber = session.ourSeq,
                    ackNumber = session.theirSeq,
                    flags = PacketUtils.TCP_FLAG_FIN or PacketUtils.TCP_FLAG_ACK
                )
                tunWriter(finPacket)
            }
            closeSession(session)
        }
    }

    private fun establishHttpTunnel(socket: Socket, targetHost: String, targetPort: Int, config: ProxyConfig): Boolean {
        val out = socket.getOutputStream()
        val inStream = socket.getInputStream()

        val req = StringBuilder()
        req.append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
        req.append("Host: $targetHost:$targetPort\r\n")
        req.append("User-Agent: ProxyVpn/2.0\r\n")
        req.append("Proxy-Connection: Keep-Alive\r\n")

        if (!config.username.isNullOrEmpty() && config.password != null) {
            val creds = "${config.username}:${config.password}"
            val encoded = Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
            req.append("Proxy-Authorization: Basic $encoded\r\n")
        }
        req.append("\r\n")

        out.write(req.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        val (statusCode, message) = TunnelHelpers.readHttpConnectStatusLine(inStream)
        TunnelHelpers.discardHttpHeaders(inStream)

        val isSuccess = statusCode == 200
        if (!isSuccess) {
            AppLogger.w(TAG, "HTTP CONNECT $targetHost:$targetPort failed: $statusCode $message")
        }
        return isSuccess
    }

    private fun establishSocks5Tunnel(socket: Socket, targetHost: String, targetPort: Int, config: ProxyConfig): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        val hasAuth = !config.username.isNullOrEmpty() && config.password != null

        if (hasAuth) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val methodResp = ByteArray(2)
        TunnelHelpers.readFully(input, methodResp, 0, 2)
        if (methodResp[0] != 0x05.toByte() || methodResp[1] == 0xFF.toByte()) return false

        if (methodResp[1] == 0x02.toByte()) {
            val userBytes = config.username.orEmpty().toByteArray(Charsets.UTF_8)
            val passBytes = config.password.orEmpty().toByteArray(Charsets.UTF_8)
            val authPkt = ByteArray(3 + userBytes.size + passBytes.size)
            authPkt[0] = 0x01
            authPkt[1] = userBytes.size.toByte()
            System.arraycopy(userBytes, 0, authPkt, 2, userBytes.size)
            authPkt[2 + userBytes.size] = passBytes.size.toByte()
            System.arraycopy(passBytes, 0, authPkt, 3 + userBytes.size, passBytes.size)
            out.write(authPkt)
            out.flush()

            val authResp = ByteArray(2)
            TunnelHelpers.readFully(input, authResp, 0, 2)
            if (authResp[1] != 0x00.toByte()) return false
        }

        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        val connectReq = ByteArray(4 + 1 + hostBytes.size + 2)
        connectReq[0] = 0x05
        connectReq[1] = 0x01
        connectReq[2] = 0x00
        connectReq[3] = 0x03
        connectReq[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.size)
        val portOffset = 5 + hostBytes.size
        connectReq[portOffset] = ((targetPort ushr 8) and 0xFF).toByte()
        connectReq[portOffset + 1] = (targetPort and 0xFF).toByte()

        out.write(connectReq)
        out.flush()

        val replyHeader = ByteArray(4)
        TunnelHelpers.readFully(input, replyHeader, 0, 4)
        if (replyHeader[1] != 0x00.toByte()) return false

        val atyp = replyHeader[3].toInt()
        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> input.read()
            else -> 4
        }
        val discard = ByteArray(addrLen + 2)
        TunnelHelpers.readFully(input, discard, 0, discard.size)
        return true
    }

    private fun sendRst(
        srcAddress: InetAddress,
        dstAddress: InetAddress,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long
    ) {
        val rstPacket = PacketUtils.buildTcpPacket(
            srcAddress = srcAddress,
            dstAddress = dstAddress,
            srcPort = srcPort,
            dstPort = dstPort,
            sequenceNumber = seq,
            ackNumber = ack,
            flags = PacketUtils.TCP_FLAG_RST or PacketUtils.TCP_FLAG_ACK
        )
        tunWriter(rstPacket)
    }

    private fun closeSession(session: TcpSession) {
        session.close()
        sessions.remove(session.sessionKey)
    }

    private fun startIdleCleanupLoop() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000L) // Check every 60 seconds
                val now = System.currentTimeMillis()
                val idleTimeout = 300_000L // 5 minutes idle timeout
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val session = entry.value
                    if (now - session.lastActivityTime > idleTimeout) {
                        AppLogger.d(TAG, "Closing idle session ${session.sessionKey}")
                        session.close()
                        iterator.remove()
                    }
                }
                DnsCache.cleanupExpired()
            }
        }
    }

    fun closeAll() {
        cleanupJob?.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        private const val TAG = "TcpRelay"
    }
}
