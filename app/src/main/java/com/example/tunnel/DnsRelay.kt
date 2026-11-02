package com.example.tunnel

import android.net.VpnService
import android.util.Base64
import com.example.model.ProxyConfig
import com.example.model.ProxyType
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DnsRelay intercepts local DNS requests on UDP port 53 and resolves them using
 * DNS-over-HTTPS (DoH) through the configured proxy (HTTP CONNECT to cloudflare-dns.com:443).
 * It extracts A records and records the IP -> domain mappings in DnsCache so that TcpRelay
 * can issue domain-based CONNECT requests, eliminating TLS certificate errors.
 */
class DnsRelay(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val proxyConfigProvider: () -> ProxyConfig,
    private val tunWriter: (ByteArray) -> Unit
) {

    private val dohHost = "cloudflare-dns.com"
    private val dohPort = 443

    fun handleDnsPacket(
        srcAddress: InetAddress,
        srcPort: Int,
        dstAddress: InetAddress,
        dstPort: Int,
        dnsQueryPayload: ByteArray
    ) {
        scope.launch(Dispatchers.IO) {
            val qname = parseDnsQName(dnsQueryPayload)
            AppLogger.d(TAG, "DNS query for '${qname ?: "unknown"}' from port $srcPort")

            var resolvedPayload: ByteArray? = null

            // 1. Primary path: Resolve via DNS-over-HTTPS through the user's proxy
            try {
                resolvedPayload = resolveDnsOverHttps(dnsQueryPayload, qname)
            } catch (e: Exception) {
                AppLogger.w(TAG, "DoH via proxy failed for '$qname': ${e.message}. Trying direct UDP fallback.")
            }

            // 2. Fallback path: Protected UDP query to 1.1.1.1:53 if DoH proxy route failed
            if (resolvedPayload == null) {
                resolvedPayload = resolveDnsViaUdp(dnsQueryPayload)
            }

            if (resolvedPayload != null) {
                // Parse A records from the response and update DnsCache
                if (!qname.isNullOrEmpty()) {
                    extractAndCacheARecords(resolvedPayload, qname)
                }

                // Construct IPv4 UDP response packet back into TUN
                val responseIpPacket = PacketUtils.buildUdpPacket(
                    srcAddress = dstAddress,
                    dstAddress = srcAddress,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    payload = resolvedPayload
                )
                tunWriter(responseIpPacket)
            } else {
                AppLogger.e(TAG, "Failed to resolve DNS query for '$qname'")
            }
        }
    }

    private fun resolveDnsOverHttps(dnsQueryPayload: ByteArray, qname: String?): ByteArray {
        val config = proxyConfigProvider()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        try {
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = 8000
            vpnService.protect(rawSocket)

            // Connect to proxy
            rawSocket.connect(InetSocketAddress(config.host, config.port), 7000)

            // Establish tunnel to cloudflare-dns.com:443 through proxy
            val tunnelOk = when (config.type) {
                ProxyType.HTTP -> establishHttpTunnel(rawSocket, dohHost, dohPort, config)
                ProxyType.SOCKS5 -> establishSocks5Tunnel(rawSocket, dohHost, dohPort, config)
            }

            if (!tunnelOk) {
                throw IllegalStateException("Proxy refused tunnel to $dohHost:$dohPort")
            }

            // Wrap established tunnel in TLS
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, dohHost, dohPort, true) as SSLSocket
            sslSocket.startHandshake()

            val out: OutputStream = sslSocket.outputStream
            val inStream: InputStream = sslSocket.inputStream

            // RFC 8484 DNS-over-HTTPS GET wire format using base64url encoding
            val base64Dns = Base64.encodeToString(
                dnsQueryPayload,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )

            val httpReq = "GET /dns-query?dns=$base64Dns HTTP/1.1\r\n" +
                    "Host: $dohHost\r\n" +
                    "Accept: application/dns-message\r\n" +
                    "User-Agent: ProxyVpn/2.0\r\n" +
                    "Connection: close\r\n\r\n"

            out.write(httpReq.toByteArray(Charsets.US_ASCII))
            out.flush()

            // Read HTTP response
            val (statusCode, _) = TunnelHelpers.readHttpConnectStatusLine(inStream)
            if (statusCode != 200) {
                throw IllegalStateException("DoH server returned HTTP $statusCode")
            }

            var contentLength = -1
            while (true) {
                val line = TunnelHelpers.readLineByteByByte(inStream)
                if (line.isEmpty() || line == "\r") break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter("content-length:").trim().toIntOrNull() ?: -1
                }
            }

            val dnsResponseBytes = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                TunnelHelpers.readFully(inStream, buf, 0, contentLength)
                buf
            } else {
                val baos = ByteArrayOutputStream()
                val temp = ByteArray(1024)
                var r: Int
                while (inStream.read(temp).also { r = it } != -1) {
                    baos.write(temp, 0, r)
                }
                baos.toByteArray()
            }

            AppLogger.i(TAG, "DoH resolved '$qname' (${dnsResponseBytes.size} bytes) via proxy")
            return dnsResponseBytes

        } finally {
            try { sslSocket?.close() } catch (_: Exception) { }
            try { rawSocket?.close() } catch (_: Exception) { }
        }
    }

    private fun resolveDnsViaUdp(dnsQueryPayload: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = 4000
            vpnService.protect(socket)

            val dnsServer = InetAddress.getByName("1.1.1.1")
            val sendPacket = DatagramPacket(dnsQueryPayload, dnsQueryPayload.size, dnsServer, 53)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(2048)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            val result = ByteArray(receivePacket.length)
            System.arraycopy(receiveBuffer, 0, result, 0, receivePacket.length)
            result
        } catch (e: Exception) {
            AppLogger.w(TAG, "Direct UDP DNS failed: ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) { }
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

        val (statusCode, _) = TunnelHelpers.readHttpConnectStatusLine(inStream)
        TunnelHelpers.discardHttpHeaders(inStream)
        return statusCode == 200
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
            0x03 -> {
                val l = input.read()
                l
            }
            else -> 4
        }
        val discard = ByteArray(addrLen + 2)
        TunnelHelpers.readFully(input, discard, 0, discard.size)
        return true
    }

    private fun parseDnsQName(data: ByteArray): String? {
        if (data.size < 12) return null
        val sb = StringBuilder()
        var pos = 12
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) break
            pos++
            if (pos + len > data.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun extractAndCacheARecords(dnsResponse: ByteArray, qname: String) {
        try {
            if (dnsResponse.size < 12) return
            val ancount = ((dnsResponse[6].toInt() and 0xFF) shl 8) or (dnsResponse[7].toInt() and 0xFF)
            if (ancount <= 0) return

            // Skip question section
            var pos = 12
            while (pos < dnsResponse.size) {
                val len = dnsResponse[pos].toInt() and 0xFF
                if (len == 0) {
                    pos += 5 // 0 byte + QTYPE(2) + QCLASS(2)
                    break
                }
                pos += 1 + len
            }

            // Loop answers
            for (i in 0 until ancount) {
                if (pos >= dnsResponse.size) break

                // Name field: check if compressed pointer (0xC0) or label
                if ((dnsResponse[pos].toInt() and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < dnsResponse.size && dnsResponse[pos] != 0.toByte()) {
                        pos += 1 + (dnsResponse[pos].toInt() and 0xFF)
                    }
                    pos += 1
                }

                if (pos + 10 > dnsResponse.size) break
                val type = ((dnsResponse[pos].toInt() and 0xFF) shl 8) or (dnsResponse[pos + 1].toInt() and 0xFF)
                val ttl = ((dnsResponse[pos + 4].toLong() and 0xFF) shl 24) or
                        ((dnsResponse[pos + 5].toLong() and 0xFF) shl 16) or
                        ((dnsResponse[pos + 6].toLong() and 0xFF) shl 8) or
                        (dnsResponse[pos + 7].toLong() and 0xFF)
                val rdLength = ((dnsResponse[pos + 8].toInt() and 0xFF) shl 8) or (dnsResponse[pos + 9].toInt() and 0xFF)
                pos += 10

                if (type == 1 && rdLength == 4 && pos + 4 <= dnsResponse.size) {
                    // Type A record (IPv4 address)
                    val ipBytes = ByteArray(4)
                    System.arraycopy(dnsResponse, pos, ipBytes, 0, 4)
                    val ip = InetAddress.getByAddress(ipBytes)
                    DnsCache.put(ip, qname, ttl)
                    AppLogger.i(TAG, "Cached DNS: $qname -> ${ip.hostAddress} (TTL: ${ttl}s)")
                }
                pos += rdLength
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Could not extract A records: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DnsRelay"
    }
}
