package com.example.tunnel

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.model.ProxyConfig
import com.example.service.VpnController
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Tun2ProxyEngine reads IP packets from the TUN interface, routes UDP DNS packets to DnsRelay,
 * routes TCP packets to TcpRelay, and drops IPv6 packets to prevent leaks.
 */
class Tun2ProxyEngine(
    private val vpnService: VpnService,
    private val pfd: ParcelFileDescriptor,
    private val proxyConfig: ProxyConfig
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunWriteLock = Any()
    private val outStream = FileOutputStream(pfd.fileDescriptor)
    private val inStream = FileInputStream(pfd.fileDescriptor)

    private var workerJob: Job? = null

    private val tunWriter: (ByteArray) -> Unit = { packet ->
        synchronized(tunWriteLock) {
            try {
                outStream.write(packet)
                outStream.flush()
                VpnController.addBytesReceived(packet.size.toLong())
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error writing packet to TUN: ${e.message}")
            }
        }
    }

    private val dnsRelay = DnsRelay(
        vpnService = vpnService,
        scope = engineScope,
        proxyConfigProvider = { proxyConfig },
        tunWriter = tunWriter
    )

    private val tcpRelay = TcpRelay(
        vpnService = vpnService,
        scope = engineScope,
        proxyConfigProvider = { proxyConfig },
        tunWriter = tunWriter
    )

    fun start() {
        workerJob?.cancel()
        workerJob = engineScope.launch {
            val buffer = ByteArray(32767)
            AppLogger.i(TAG, "Tun2ProxyEngine started")

            try {
                while (isActive) {
                    val bytesRead = inStream.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead <= 0) continue

                    // Drop IPv6 packets to prevent leaks
                    if (PacketUtils.isIpv6(buffer, bytesRead)) {
                        // IPv6 dropped per security requirements to prevent leaks
                        continue
                    }

                    VpnController.addBytesSent(bytesRead.toLong())

                    val parsedIp = PacketUtils.parseIpv4Header(buffer, bytesRead) ?: continue

                    when (parsedIp.protocol) {
                        PacketUtils.IP_PROTO_UDP -> {
                            val parsedUdp = PacketUtils.parseUdp(buffer, parsedIp.headerLength, bytesRead)
                            if (parsedUdp != null && parsedUdp.destPort == 53 && parsedUdp.payloadLength > 0) {
                                val dnsPayload = ByteArray(parsedUdp.payloadLength)
                                System.arraycopy(buffer, parsedUdp.payloadOffset, dnsPayload, 0, parsedUdp.payloadLength)

                                dnsRelay.handleDnsPacket(
                                    srcAddress = parsedIp.sourceAddress,
                                    srcPort = parsedUdp.sourcePort,
                                    dstAddress = parsedIp.destAddress,
                                    dstPort = parsedUdp.destPort,
                                    dnsQueryPayload = dnsPayload
                                )
                            }
                        }
                        PacketUtils.IP_PROTO_TCP -> {
                            val parsedTcp = PacketUtils.parseTcp(buffer, parsedIp.headerLength, bytesRead)
                            if (parsedTcp != null) {
                                tcpRelay.handleTcpPacket(parsedIp, parsedTcp, buffer)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    AppLogger.d(TAG, "TUN read closed: ${e.message}")
                }
            } catch (e: Exception) {
                if (isActive) {
                    AppLogger.e(TAG, "TUN worker error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        workerJob?.cancel()
        tcpRelay.closeAll()
        engineScope.cancel()
        try { inStream.close() } catch (_: Exception) { }
        try { outStream.close() } catch (_: Exception) { }
        AppLogger.i(TAG, "Tun2ProxyEngine stopped")
    }

    companion object {
        private const val TAG = "Tun2ProxyEngine"
    }
}
