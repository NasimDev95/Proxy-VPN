package com.example.tunnel

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.net.InetAddress
import java.net.Socket

enum class SessionState {
    CONNECTING,
    CONNECTED,
    HALF_CLOSED,
    CLOSED
}

class TcpSession(
    val sessionKey: String,
    val clientAddress: InetAddress,
    val clientPort: Int,
    val remoteAddress: InetAddress,
    val remotePort: Int,
    var ourSeq: Long,
    var theirSeq: Long
) {
    @Volatile
    var state: SessionState = SessionState.CONNECTING

    @Volatile
    var proxySocket: Socket? = null

    @Volatile
    var targetHost: String? = null

    @Volatile
    var lastActivityTime: Long = System.currentTimeMillis()

    // Ordered FIFO channel for sending payloads to proxy
    val outboundChannel = Channel<ByteArray>(Channel.UNLIMITED)

    var readerJob: Job? = null
    var writerJob: Job? = null

    fun markActive() {
        lastActivityTime = System.currentTimeMillis()
    }

    @Synchronized
    fun close() {
        if (state == SessionState.CLOSED) return
        state = SessionState.CLOSED
        outboundChannel.close()
        readerJob?.cancel()
        writerJob?.cancel()
        try {
            proxySocket?.close()
        } catch (_: Exception) { }
        proxySocket = null
    }
}
