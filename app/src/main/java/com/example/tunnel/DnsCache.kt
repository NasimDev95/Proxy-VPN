package com.example.tunnel

import com.example.util.AppLogger
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * DnsCache maintains the mapping of resolved IPv4 addresses to domain names with TTL.
 * This enables TcpRelay to issue domain-based HTTP CONNECT / SOCKS5 requests rather than IP-based,
 * preventing TLS certificate errors (e.g., for https://aistudio.google.com).
 */
object DnsCache {

    private const val TAG = "DnsCache"
    private const val MIN_TTL_SECONDS = 60L
    private const val MAX_TTL_SECONDS = 86400L

    private data class CacheEntry(
        val domain: String,
        val expireAtMillis: Long
    )

    private val ipToDomainMap = ConcurrentHashMap<String, CacheEntry>()

    fun put(ip: InetAddress, domain: String, ttlSeconds: Long) {
        put(ip.hostAddress ?: return, domain, ttlSeconds)
    }

    fun put(ipStr: String, domain: String, ttlSeconds: Long) {
        val normalizedDomain = domain.trim().trimEnd('.')
        if (normalizedDomain.isEmpty()) return

        val clampedTtl = ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        val expireAt = System.currentTimeMillis() + (clampedTtl * 1000L)

        ipToDomainMap[ipStr] = CacheEntry(
            domain = normalizedDomain,
            expireAtMillis = expireAt
        )
        AppLogger.d(TAG, "Cached IP $ipStr -> $normalizedDomain (TTL: ${clampedTtl}s)")
    }

    fun get(ip: InetAddress): String? {
        val hostAddress = ip.hostAddress ?: return null
        return get(hostAddress)
    }

    fun get(ipStr: String): String? {
        val entry = ipToDomainMap[ipStr] ?: return null
        if (System.currentTimeMillis() > entry.expireAtMillis) {
            ipToDomainMap.remove(ipStr)
            AppLogger.d(TAG, "Expired mapping for IP $ipStr (domain: ${entry.domain})")
            return null
        }
        return entry.domain
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val iterator = ipToDomainMap.entries.iterator()
        var removedCount = 0
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (now > item.value.expireAtMillis) {
                iterator.remove()
                removedCount++
            }
        }
        if (removedCount > 0) {
            AppLogger.d(TAG, "Cleaned up $removedCount expired DNS cache entries")
        }
    }

    fun clear() {
        ipToDomainMap.clear()
    }
}
