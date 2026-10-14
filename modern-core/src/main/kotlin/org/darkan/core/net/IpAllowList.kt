package org.darkan.core.net

import java.net.InetAddress

class IpAllowList(spec: String) {
    private class Range(val network: ByteArray, val prefixBits: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            val fullBytes = prefixBits / 8
            for (i in 0 until fullBytes)
                if (address[i] != network[i]) return false
            val remaining = prefixBits % 8
            if (remaining == 0) return true
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
        }
    }

    private val ranges: List<Range> = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::parse)

    val isEmpty get() = ranges.isEmpty()

    fun contains(ip: String): Boolean {
        if (ranges.isEmpty()) return false
        val address = literal(ip.trim().removePrefix("[").removeSuffix("]").substringBefore('%')) ?: return false
        return ranges.any { it.contains(address) }
    }

    private fun parse(entry: String): Range {
        val host = entry.substringBefore('/')
        val address = literal(host) ?: throw IllegalArgumentException("Not an IP address: $entry")
        val bits = if ('/' in entry) entry.substringAfter('/').toInt() else address.size * 8
        require(bits in 0..address.size * 8) { "Bad prefix length: $entry" }
        return Range(address, bits)
    }

    companion object {
        fun literal(ip: String): ByteArray? {
            if (ip.isEmpty() || !(ip.contains(':') || ip.all { it.isDigit() || it == '.' })) return null
            return runCatching { InetAddress.getByName(ip).address }.getOrNull()
        }
    }
}
