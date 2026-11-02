package com.example.service

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    object Connecting : VpnStatus()
    object Connected : VpnStatus()
    data class Error(val message: String) : VpnStatus()

    val label: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting"
            is Connected -> "Connected"
            is Error -> "Error"
        }
}
