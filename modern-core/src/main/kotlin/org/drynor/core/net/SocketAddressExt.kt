package org.drynor.core.net

import io.ktor.network.sockets.SocketAddress

/**
 * Best-effort extraction of the raw host/IP portion from a Ktor [SocketAddress] without relying on reverse DNS.
 *
 * Ktor's `remoteAddress.toString()` formats vary by platform, commonly:
 * - `127.0.0.1:12345`
 * - `/127.0.0.1:12345`
 * - `[::1]:12345`
 * - `/[::1]:12345`
 */
fun SocketAddress.ipString(): String {
    var s = this.toString().trim()
    if (s.isEmpty()) return s
    val slash = s.lastIndexOf('/')
    if (slash >= 0 && slash < s.length - 1) {
        s = s.substring(slash + 1)
    }

    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end > 1) {
            return s.substring(1, end).substringBefore('%')
        }
        return s.removePrefix("[").removeSuffix("]").substringBefore('%')
    }

    // If it ends with :<port>, strip the port.
    val lastColon = s.lastIndexOf(':')
    if (lastColon > 0 && lastColon < s.length - 1) {
        val portPart = s.substring(lastColon + 1)
        if (portPart.all { it.isDigit() }) {
            // Only strip if it looks like ipv4-or-hostname. For raw ipv6 without brackets, avoid corrupting the address.
            val candidate = s.substring(0, lastColon)
            if (!candidate.contains(':')) {
                return candidate.substringBefore('%')
            }
        }
    }

    return s.substringBefore('%')
}


