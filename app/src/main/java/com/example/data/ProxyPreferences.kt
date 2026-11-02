package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.ProxyConfig
import com.example.util.CryptoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProxyPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<ProxyConfig> = _configFlow.asStateFlow()

    private val _bypassAppsFlow = MutableStateFlow(loadBypassApps())
    val bypassAppsFlow: StateFlow<Set<String>> = _bypassAppsFlow.asStateFlow()

    fun loadConfig(): ProxyConfig {
        val rawUrl = prefs.getString(KEY_PROXY_URL, ProxyConfig.DEFAULT_URL) ?: ProxyConfig.DEFAULT_URL
        val username = prefs.getString(KEY_USERNAME, null)
        val encryptedPassword = prefs.getString(KEY_PASSWORD_ENCRYPTED, null)
        val decryptedPassword = if (!encryptedPassword.isNullOrEmpty()) {
            CryptoManager.decrypt(encryptedPassword)
        } else {
            null
        }

        val validation = ProxyConfig.validate(rawUrl, username, decryptedPassword)
        return validation.getOrElse {
            ProxyConfig.validate(ProxyConfig.DEFAULT_URL).getOrThrow()
        }
    }

    fun saveConfig(config: ProxyConfig) {
        prefs.edit().apply {
            putString(KEY_PROXY_URL, config.rawUrl)
            if (config.username != null) {
                putString(KEY_USERNAME, config.username)
            } else {
                remove(KEY_USERNAME)
            }
            if (!config.password.isNullOrEmpty()) {
                val encrypted = CryptoManager.encrypt(config.password)
                putString(KEY_PASSWORD_ENCRYPTED, encrypted)
            } else {
                remove(KEY_PASSWORD_ENCRYPTED)
            }
            apply()
        }
        _configFlow.value = config
    }

    fun loadBypassApps(): Set<String> {
        val stored = prefs.getStringSet(KEY_BYPASS_APPS, null)
        return if (stored != null) {
            stored.toSet()
        } else {
            DEFAULT_BYPASS_APPS
        }
    }

    fun saveBypassApps(apps: Set<String>) {
        prefs.edit().putStringSet(KEY_BYPASS_APPS, apps).apply()
        _bypassAppsFlow.value = apps
    }

    fun addBypassApp(pkg: String) {
        val trimmed = pkg.trim()
        if (trimmed.isEmpty()) return
        val current = loadBypassApps().toMutableSet()
        current.add(trimmed)
        saveBypassApps(current)
    }

    fun removeBypassApp(pkg: String) {
        val current = loadBypassApps().toMutableSet()
        current.remove(pkg.trim())
        saveBypassApps(current)
    }

    companion object {
        private const val PREFS_NAME = "proxy_vpn_preferences"
        private const val KEY_PROXY_URL = "key_proxy_url"
        private const val KEY_USERNAME = "key_username"
        private const val KEY_PASSWORD_ENCRYPTED = "key_password_encrypted"
        private const val KEY_BYPASS_APPS = "key_bypass_apps"

        val DEFAULT_BYPASS_APPS = setOf("com.termux")

        @Volatile
        private var instance: ProxyPreferences? = null

        fun getInstance(context: Context): ProxyPreferences {
            return instance ?: synchronized(this) {
                instance ?: ProxyPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
