package com.example.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ProxyPreferences
import com.example.model.ProxyConfig
import com.example.net.ProxyTestResult
import com.example.net.ProxyTester
import com.example.service.VpnController
import com.example.service.VpnStatus
import com.example.util.AppLogger
import com.example.util.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = ProxyPreferences.getInstance(application)

    val vpnStatus: StateFlow<VpnStatus> = VpnController.status
    val bytesSent: StateFlow<Long> = VpnController.bytesSent
    val bytesReceived: StateFlow<Long> = VpnController.bytesReceived
    val activeProxyUrl: StateFlow<String> = VpnController.activeProxyUrl

    val savedConfig: StateFlow<ProxyConfig> = preferences.configFlow
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            preferences.loadConfig()
        )

    val bypassApps: StateFlow<Set<String>> = preferences.bypassAppsFlow
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            preferences.loadBypassApps()
        )

    val logs: StateFlow<List<LogEntry>> = AppLogger.logsFlow
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList()
        )

    private val _urlInput = MutableStateFlow(savedConfig.value.rawUrl)
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _usernameInput = MutableStateFlow(savedConfig.value.username ?: "")
    val usernameInput: StateFlow<String> = _usernameInput.asStateFlow()

    private val _passwordInput = MutableStateFlow(savedConfig.value.password ?: "")
    val passwordInput: StateFlow<String> = _passwordInput.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testResult = MutableStateFlow<ProxyTestResult?>(null)
    val testResult: StateFlow<ProxyTestResult?> = _testResult.asStateFlow()

    init {
        val current = savedConfig.value
        _urlInput.value = current.rawUrl
        _usernameInput.value = current.username ?: ""
        _passwordInput.value = current.password ?: ""
    }

    fun onUrlChanged(newUrl: String) {
        _urlInput.value = newUrl
        _urlError.value = null
        _saveMessage.value = null
    }

    fun onUsernameChanged(newUsername: String) {
        _usernameInput.value = newUsername
        _saveMessage.value = null
    }

    fun onPasswordChanged(newPassword: String) {
        _passwordInput.value = newPassword
        _saveMessage.value = null
    }

    fun saveSettings(): Boolean {
        val validation = ProxyConfig.validate(
            urlInput = _urlInput.value,
            usernameInput = _usernameInput.value,
            passwordInput = _passwordInput.value
        )

        return validation.fold(
            onSuccess = { validConfig ->
                _urlError.value = null
                preferences.saveConfig(validConfig)
                _saveMessage.value = "Settings saved successfully!"
                AppLogger.i("Settings", "Updated proxy settings: ${validConfig.displayUrl}")
                true
            },
            onFailure = { error ->
                _urlError.value = error.message ?: "Invalid proxy URL format"
                _saveMessage.value = null
                false
            }
        )
    }

    fun addBypassApp(pkg: String) {
        preferences.addBypassApp(pkg)
        AppLogger.i("Settings", "Added bypass app: $pkg")
    }

    fun removeBypassApp(pkg: String) {
        preferences.removeBypassApp(pkg)
        AppLogger.i("Settings", "Removed bypass app: $pkg")
    }

    fun resetBypassApps() {
        preferences.saveBypassApps(ProxyPreferences.DEFAULT_BYPASS_APPS)
        AppLogger.i("Settings", "Reset bypass apps to default: ${ProxyPreferences.DEFAULT_BYPASS_APPS}")
    }

    fun testConnection() {
        val validation = ProxyConfig.validate(
            urlInput = _urlInput.value,
            usernameInput = _usernameInput.value,
            passwordInput = _passwordInput.value
        )

        validation.fold(
            onSuccess = { config ->
                _urlError.value = null
                _isTesting.value = true
                _testResult.value = null

                viewModelScope.launch {
                    val result = ProxyTester.testConnection(config)
                    _testResult.value = result
                    _isTesting.value = false
                    AppLogger.i("ProxyTest", "Test result: ${result.statusText} (${result.latencyMs}ms) - ${result.details}")
                }
            },
            onFailure = { error ->
                _urlError.value = error.message ?: "Invalid proxy URL format"
            }
        )
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun copyLogsToClipboard(context: Context): String {
        val allLogs = AppLogger.getAllLogsAsText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ProxyVpn Logs", allLogs)
        clipboard.setPrimaryClip(clip)
        return "Logs copied to clipboard (${allLogs.lines().size} lines)"
    }

    fun clearLogs() {
        AppLogger.clear()
    }

    fun toggleVpnConnection(context: Context, onPrepareVpn: (Intent) -> Unit) {
        when (vpnStatus.value) {
            is VpnStatus.Connected, is VpnStatus.Connecting -> {
                VpnController.stopVpn(context)
            }
            is VpnStatus.Disconnected, is VpnStatus.Error -> {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    onPrepareVpn(prepareIntent)
                } else {
                    startVpn(context)
                }
            }
        }
    }

    fun startVpn(context: Context) {
        VpnController.startVpn(context)
    }

    fun stopVpn(context: Context) {
        VpnController.stopVpn(context)
    }
}
