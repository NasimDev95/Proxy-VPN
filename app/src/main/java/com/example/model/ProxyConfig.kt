package com.example.model

import java.net.URI

enum class ProxyType {
    HTTP,
    SOCKS5
}

data class ProxyConfig(
    val rawUrl: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null
) {
    val displayUrl: String
        get() = "${type.name.lowercase()}://$host:$port"

    val hasAuth: Boolean
        get() = !username.isNullOrBlank()

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8080"

        fun validate(
            urlInput: String,
            usernameInput: String? = null,
            passwordInput: String? = null
        ): Result<ProxyConfig> {
            val trimmed = urlInput.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Proxy URL cannot be empty"))
            }

            val lower = trimmed.lowercase()
            val type = when {
                lower.startsWith("http://") -> ProxyType.HTTP
                lower.startsWith("socks5://") -> ProxyType.SOCKS5
                else -> {
                    return Result.failure(
                        IllegalArgumentException("Invalid scheme. URL must start with http:// or socks5://")
                    )
                }
            }

            try {
                // Parse URI
                val uri = URI(trimmed)
                val host = uri.host
                val port = uri.port

                if (host.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("Host cannot be empty"))
                }

                if (port == -1) {
                    return Result.failure(
                        IllegalArgumentException("Port is missing. Format must be scheme://host:port")
                    )
                }

                if (port !in 1..65535) {
                    return Result.failure(
                        IllegalArgumentException("Port must be between 1 and 65535 (got $port)")
                    )
                }

                val u = usernameInput?.trim().takeIf { !it.isNullOrEmpty() }
                val p = passwordInput?.takeIf { it.isNotEmpty() }

                return Result.success(
                    ProxyConfig(
                        rawUrl = trimmed,
                        type = type,
                        host = host,
                        port = port,
                        username = u,
                        password = p
                    )
                )
            } catch (e: Exception) {
                return Result.failure(
                    IllegalArgumentException(e.message ?: "Invalid proxy URL format")
                )
            }
        }
    }
}
