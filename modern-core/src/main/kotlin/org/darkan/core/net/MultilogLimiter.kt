package org.darkan.core.net

import org.darkan.core.EnvVars
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple IP-based concurrent connection limiter.
 * Tracks active connections per IP address and enforces a limit.
 * Connections are removed when disconnect() is called.
 */
class MultilogLimiter {
    private val connected = ConcurrentHashMap<String, Int>()
    private val whitelistedIps: Set<String> = parseWhitelist(EnvVars.multiLogWhitelist)

    private fun normalizeIpKey(ip: String): String {
        var s = ip.trim()
        if (s.isEmpty()) return s
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
        s = s.substringBefore('%') // strip ipv6 scope id
        // canonicalize ipv6 loopback long form
        if (s == "0:0:0:0:0:0:0:1") s = "::1"
        return s
    }

    private fun parseWhitelist(whitelistStr: String?): Set<String> =
        whitelistStr
            ?.split(',')
            ?.map { normalizeIpKey(it.trim()) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /**
     * Attempts to add a connection for the given IP.
     * @return true if the connection was allowed, false if limit exceeded
     */
    fun add(ip: String): Boolean {
        val key = normalizeIpKey(ip)
        if (key.isEmpty() || key in whitelistedIps) return true
        
        var allowed = false
        connected.compute(key) { _, count ->
            val current = count ?: 0
            if (current < EnvVars.multiLogLimit) {
                allowed = true
                current + 1
            } else {
                allowed = false
                current // Keep current count, don't increment
            }
        }
        return allowed
    }

    /**
     * Removes a connection for the given IP.
     * Called when a connection disconnects.
     */
    fun remove(ip: String) {
        val key = normalizeIpKey(ip)
        if (key.isEmpty() || key in whitelistedIps) return
        
        connected.computeIfPresent(key) { _, count ->
            if (count <= 1) null else count - 1
        }
    }

    fun clear() = connected.clear()

    fun getCount(ip: String): Int = connected[normalizeIpKey(ip)] ?: 0
}