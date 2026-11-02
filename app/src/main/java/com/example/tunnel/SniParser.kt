package com.example.tunnel

import com.example.util.AppLogger

/**
 * SniParser extracts the TLS Server Name Indication (SNI) from a TLS ClientHello packet.
 * When DnsCache does not have a domain for a destination IP (e.g., pre-cached IP or direct IP request),
 * this allows TcpRelay to inspect the initial ClientHello and obtain the true hostname (e.g. aistudio.google.com),
 * sending domain-based CONNECT to the proxy and eliminating TLS certificate mismatch errors.
 */
object SniParser {

    private const val TAG = "SniParser"

    fun extractSni(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String? {
        val end = offset + length
        try {
            // Minimum TLS record + handshake header is at least 43 bytes
            if (length < 43) return null

            // 1. Verify TLS Handshake record (0x16)
            if (data[offset].toInt() and 0xFF != 0x16) return null

            // TLS major version check (version >= 3.0, i.e. 0x03)
            val tlsMajor = data[offset + 1].toInt() and 0xFF
            if (tlsMajor != 3) return null

            // 2. Verify Handshake Type: ClientHello (0x01)
            var pos = offset + 5 // Skip TLS record header (ContentType 1b, Version 2b, Length 2b)
            if (pos >= end || data[pos].toInt() and 0xFF != 0x01) return null

            pos += 4 // Skip Handshake Type (1b) and 3-byte Handshake length
            pos += 2 // Skip Client Version (2b)
            pos += 32 // Skip Random bytes (32b)

            if (pos >= end) return null

            // 3. Skip Session ID
            val sessionIdLen = data[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen
            if (pos + 2 > end) return null

            // 4. Skip Cipher Suites
            val cipherSuitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen
            if (pos + 1 > end) return null

            // 5. Skip Compression Methods
            val compMethodsLen = data[pos].toInt() and 0xFF
            pos += 1 + compMethodsLen
            if (pos + 2 > end) return null

            // 6. Extensions
            val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val extensionsEnd = (pos + extensionsLen).coerceAtMost(end)

            while (pos + 4 <= extensionsEnd) {
                val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 4

                if (extType == 0) {
                    // Extension: server_name (0)
                    if (pos + 5 <= extensionsEnd) {
                        // Skip 2-byte server name list length
                        val nameType = data[pos + 2].toInt() and 0xFF
                        if (nameType == 0) { // 0 = host_name
                            val hostNameLen = ((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
                            val hostStart = pos + 5
                            if (hostStart + hostNameLen <= extensionsEnd) {
                                val sni = String(data, hostStart, hostNameLen, Charsets.US_ASCII)
                                AppLogger.d(TAG, "Extracted SNI from ClientHello: $sni")
                                return sni
                            }
                        }
                    }
                }
                pos += extLen
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "SNI parsing ended without match: ${e.message}")
        }
        return null
    }
}
