package com.example.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

object VpnController {

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val sentCounter = AtomicLong(0L)
    private val _bytesSent = MutableStateFlow(0L)
    val bytesSent: StateFlow<Long> = _bytesSent.asStateFlow()

    private val receivedCounter = AtomicLong(0L)
    private val _bytesReceived = MutableStateFlow(0L)
    val bytesReceived: StateFlow<Long> = _bytesReceived.asStateFlow()

    private val _activeProxyUrl = MutableStateFlow("")
    val activeProxyUrl: StateFlow<String> = _activeProxyUrl.asStateFlow()

    fun setStatus(newStatus: VpnStatus) {
        _status.value = newStatus
    }

    fun setActiveProxyUrl(url: String) {
        _activeProxyUrl.value = url
    }

    fun addBytesSent(bytes: Long) {
        val total = sentCounter.addAndGet(bytes)
        _bytesSent.update { total }
    }

    fun addBytesReceived(bytes: Long) {
        val total = receivedCounter.addAndGet(bytes)
        _bytesReceived.update { total }
    }

    fun resetStats() {
        sentCounter.set(0L)
        receivedCounter.set(0L)
        _bytesSent.value = 0L
        _bytesReceived.value = 0L
    }

    fun startVpn(context: Context) {
        val intent = Intent(context, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_STOP
        }
        // Use startService for stopping so background/stop transitions don't enforce startForeground
        context.startService(intent)
    }
}
