package com.example.net

import android.util.Base64
import com.example.model.ProxyConfig
import com.example.model.ProxyType
import com.example.tunnel.TunnelHelpers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class ProxyTestResult(
    val isSuccess: Boolean,
    val statusCode: Int?,
    val statusText: String,
    val latencyMs: Long,
    val details: String
)

object ProxyTester {

    private const val TARGET_HOST = "example.com"
    private const val TARGET_PORT = 443
    private const val TIMEOUT_MS = 6000

    suspend fun testConnection(config: ProxyConfig): ProxyTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var socket: Socket? = null

        try {
            socket = Socket()
            socket.soTimeout = TIMEOUT_MS
            val socketAddress = InetSocketAddress(config.host, config.port)
            socket.connect(socketAddress, TIMEOUT_MS)

            val result = when (config.type) {
                ProxyType.HTTP -> testHttpProxy(socket, config, startTime)
                ProxyType.SOCKS5 -> testSocks5Proxy(socket, config, startTime)
            }
            result
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ProxyTestResult(
                isSuccess = false,
                statusCode = null,
                statusText = "Connection Failed",
                latencyMs = latency,
                details = e.message ?: "Failed to connect to ${config.host}:${config.port}"
            )
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) { }
        }
    }

    private fun testHttpProxy(
        socket: Socket,
        config: ProxyConfig,
        startTime: Long
    ): ProxyTestResult {
        val out: OutputStream = socket.getOutputStream()
        val inStream: InputStream = socket.getInputStream()

        // Build HTTP CONNECT request
        val requestBuilder = StringBuilder()
        requestBuilder.append("CONNECT $TARGET_HOST:$TARGET_PORT HTTP/1.1\r\n")
        requestBuilder.append("Host: $TARGET_HOST:$TARGET_PORT\r\n")
        requestBuilder.append("User-Agent: ProxyVpn/2.0\r\n")
        requestBuilder.append("Proxy-Connection: Keep-Alive\r\n")

        if (!config.username.isNullOrEmpty() && config.password != null) {
            val credentials = "${config.username}:${config.password}"
            val authHeader = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
            requestBuilder.append("Proxy-Authorization: Basic $authHeader\r\n")
        }
        requestBuilder.append("\r\n")

        out.write(requestBuilder.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        // Byte-level read of status line
        val (code, text) = TunnelHelpers.readHttpConnectStatusLine(inStream)
        TunnelHelpers.discardHttpHeaders(inStream)

        val latency = System.currentTimeMillis() - startTime
        val isSuccess = code in 200..299

        val details = when (code) {
            200 -> "HTTP 200 OK — Tunnel established to $TARGET_HOST:$TARGET_PORT"
            407 -> "HTTP 407 Proxy Authentication Required — Check username/password"
            403 -> "HTTP 403 Forbidden — Proxy rejected connection to $TARGET_HOST:$TARGET_PORT"
            502 -> "HTTP 502 Bad Gateway — Proxy could not reach target"
            504 -> "HTTP 504 Gateway Timeout — Target took too long to respond"
            -1 -> "Proxy closed connection without a valid HTTP response"
            else -> "HTTP $code $text"
        }

        return ProxyTestResult(
            isSuccess = isSuccess,
            statusCode = if (code != -1) code else null,
            statusText = text,
            latencyMs = latency,
            details = details
        )
    }

    private fun testSocks5Proxy(
        socket: Socket,
        config: ProxyConfig,
        startTime: Long
    ): ProxyTestResult {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val hasAuth = !config.username.isNullOrEmpty() && config.password != null

        // 1. Initial greeting
        if (hasAuth) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val response = ByteArray(2)
        TunnelHelpers.readFully(input, response, 0, 2)
        if (response[0] != 0x05.toByte()) {
            return ProxyTestResult(
                isSuccess = false,
                statusCode = null,
                statusText = "Protocol Mismatch",
                latencyMs = System.currentTimeMillis() - startTime,
                details = "Remote server is not a valid SOCKS5 proxy"
            )
        }

        val method = response[1]
        if (method == 0xFF.toByte()) {
            return ProxyTestResult(
                isSuccess = false,
                statusCode = 401,
                statusText = "No Acceptable Auth Methods",
                latencyMs = System.currentTimeMillis() - startTime,
                details = "Proxy requires authentication that was not provided"
            )
        }

        // Handle username/password subnegotiation
        if (method == 0x02.toByte()) {
            val userBytes = config.username.orEmpty().toByteArray(Charsets.UTF_8)
            val passBytes = config.password.orEmpty().toByteArray(Charsets.UTF_8)

            val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
            authPacket[0] = 0x01
            authPacket[1] = userBytes.size.toByte()
            System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
            authPacket[2 + userBytes.size] = passBytes.size.toByte()
            System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)

            out.write(authPacket)
            out.flush()

            val authResp = ByteArray(2)
            TunnelHelpers.readFully(input, authResp, 0, 2)
            if (authResp[1] != 0x00.toByte()) {
                return ProxyTestResult(
                    isSuccess = false,
                    statusCode = 407,
                    statusText = "SOCKS5 Auth Failed",
                    latencyMs = System.currentTimeMillis() - startTime,
                    details = "Invalid username or password"
                )
            }
        }

        // 2. Send CONNECT request to example.com:443
        val domainBytes = TARGET_HOST.toByteArray(Charsets.UTF_8)
        val connectReq = ByteArray(4 + 1 + domainBytes.size + 2)
        connectReq[0] = 0x05
        connectReq[1] = 0x01
        connectReq[2] = 0x00
        connectReq[3] = 0x03
        connectReq[4] = domainBytes.size.toByte()
        System.arraycopy(domainBytes, 0, connectReq, 5, domainBytes.size)
        val portOffset = 5 + domainBytes.size
        connectReq[portOffset] = ((TARGET_PORT shr 8) and 0xFF).toByte()
        connectReq[portOffset + 1] = (TARGET_PORT and 0xFF).toByte()

        out.write(connectReq)
        out.flush()

        // 3. Read connect response with readFully
        val replyHeader = ByteArray(4)
        TunnelHelpers.readFully(input, replyHeader, 0, 4)
        val latency = System.currentTimeMillis() - startTime

        if (replyHeader[0] != 0x05.toByte()) {
            return ProxyTestResult(
                isSuccess = false,
                statusCode = null,
                statusText = "Connect Failed",
                latencyMs = latency,
                details = "Failed to receive SOCKS5 connect response"
            )
        }

        val rep = replyHeader[1].toInt()
        val isSuccess = rep == 0x00
        val (code, statusDesc) = when (rep) {
            0x00 -> Pair(200, "Connection succeeded")
            0x01 -> Pair(500, "General SOCKS server failure")
            0x02 -> Pair(403, "Connection not allowed by ruleset")
            0x03 -> Pair(503, "Network unreachable")
            0x04 -> Pair(504, "Host unreachable")
            0x05 -> Pair(502, "Connection refused")
            0x06 -> Pair(504, "TTL expired")
            0x07 -> Pair(501, "Command not supported")
            0x08 -> Pair(501, "Address type not supported")
            else -> Pair(520, "SOCKS5 error code $rep")
        }

        return ProxyTestResult(
            isSuccess = isSuccess,
            statusCode = code,
            statusText = if (isSuccess) "Connected" else "Failed",
            latencyMs = latency,
            details = "SOCKS5 $statusDesc to $TARGET_HOST:$TARGET_PORT"
        )
    }
}
