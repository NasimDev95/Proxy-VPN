package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ProxyPreferences
import com.example.model.ProxyConfig
import com.example.net.ProxyTester
import com.example.tunnel.Tun2ProxyEngine
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ProxyVpnService implements Android's VpnService to create a local virtual TUN interface
 * and route device network traffic through the configured remote proxy.
 *
 * Android 14 (API 34) Foreground Service Type Justification:
 * Google requires all foreground services targeting Android 14+ to specify a valid foregroundServiceType.
 * A VPN service encapsulates whole-device networking and does not fit mediaPlayback, dataSync,
 * location, or other standard categories. Therefore, Google requires the use of:
 * - android:foregroundServiceType="specialUse"
 * - <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn" />
 * In Kotlin code, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE must be passed to startForeground().
 */
class ProxyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunEngine: Tun2ProxyEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                startVpnTunnel()
            }
            ACTION_STOP -> {
                stopVpnTunnel(targetStatus = VpnStatus.Disconnected)
            }
        }

        return START_NOT_STICKY
    }

    private fun startVpnTunnel() {
        if (VpnController.status.value is VpnStatus.Connected) {
            AppLogger.d(TAG, "VPN already connected; refreshing notification")
            val currentUrl = VpnController.activeProxyUrl.value
            val notification = buildNotification("Connected: $currentUrl")
            startServiceInForeground(notification)
            return
        }

        VpnController.setStatus(VpnStatus.Connecting)

        serviceScope.launch {
            try {
                val preferences = ProxyPreferences.getInstance(applicationContext)
                val config = preferences.loadConfig()
                VpnController.setActiveProxyUrl(config.displayUrl)

                // Start foreground notification immediately as required by Android
                val notification = buildNotification("Testing proxy connection: ${config.displayUrl}...")
                startServiceInForeground(notification)

                // 1. Pre-connection validation: verify proxy is reachable before intercepting traffic
                AppLogger.i(TAG, "Testing proxy connectivity to ${config.displayUrl} before establishing TUN...")
                val testResult = ProxyTester.testConnection(config)
                if (!testResult.isSuccess) {
                    val errorMsg = "Proxy unreachable: ${testResult.details}"
                    AppLogger.e(TAG, errorMsg)
                    // Preserve error status; do not let stopVpnTunnel overwrite it with Disconnected
                    stopVpnTunnel(targetStatus = VpnStatus.Error(errorMsg))
                    return@launch
                }

                AppLogger.i(TAG, "Proxy verified successfully (${testResult.latencyMs}ms). Building TUN interface...")

                // 2. Build TUN interface with IPv4, IPv6 leak protection, and DNS
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(1500)
                    .addAddress("10.8.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    // IPv6 leak protection: route all IPv6 into TUN to be dropped by Tun2ProxyEngine
                    .addAddress("fd00::2", 128)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")

                // 3. Bypass applications: Termux, this app itself, and user-configured bypass apps
                val bypassList = preferences.loadBypassApps().toMutableSet()
                bypassList.add(packageName) // Prevent packet loop for this app's proxy connections
                bypassList.add("com.termux") // Ensure Termux is always bypassed to prevent loops

                for (pkg in bypassList) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        AppLogger.i(TAG, "Disallowed (bypassed) package: $pkg")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Could not bypass package '$pkg' (not installed?): ${e.message}")
                    }
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    val errorMsg = "Failed to establish TUN interface (Builder.establish returned null)"
                    AppLogger.e(TAG, errorMsg)
                    stopVpnTunnel(targetStatus = VpnStatus.Error(errorMsg))
                    return@launch
                }

                vpnInterface = pfd
                VpnController.setStatus(VpnStatus.Connected)

                // Update notification with Connected state
                updateNotification("Connected: ${config.displayUrl}")

                // 4. Start pure-Kotlin Tun2ProxyEngine (DoH DNS, ordered TCP relay, domain CONNECT)
                tunEngine = Tun2ProxyEngine(
                    vpnService = this@ProxyVpnService,
                    pfd = pfd,
                    proxyConfig = config
                ).also { it.start() }

            } catch (e: Exception) {
                val errorMsg = e.message ?: "VPN Initialization failed"
                AppLogger.e(TAG, "Error starting VPN: $errorMsg")
                stopVpnTunnel(targetStatus = VpnStatus.Error(errorMsg))
            }
        }
    }

    private fun stopVpnTunnel(targetStatus: VpnStatus = VpnStatus.Disconnected) {
        try {
            tunEngine?.stop()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error stopping TUN engine: ${e.message}")
        }
        tunEngine = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null

        // Preserve error status if stop was triggered by a connection failure
        VpnController.setStatus(targetStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startServiceInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, ProxyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.disconnect),
                disconnectPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnTunnel(targetStatus = VpnStatus.Disconnected)
        serviceScope.cancel()
    }

    override fun onRevoke() {
        super.onRevoke()
        AppLogger.i(TAG, "VPN permission revoked by system or user")
        stopVpnTunnel(targetStatus = VpnStatus.Disconnected)
    }

    companion object {
        const val TAG = "ProxyVpnService"
        const val ACTION_START = "com.example.proxyvpn.START"
        const val ACTION_STOP = "com.example.proxyvpn.STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "proxy_vpn_notification_channel"
    }
}
