package org.darkan.cs2

/**
 * CS2 string constants are raw bytes on the wire. Everything in this package
 * keeps them as latin-1 strings (one char per byte) so re-encoding is
 * byte-identical; only the TypeScript literal form is escaped for display.
 *
 * The client maps bytes 0x80..0x9F through a cp1252 table when it renders text,
 * but that mapping is lossy, so it is deliberately not applied here.
 */
object Cs2Strings {

    /** Escape a raw string into the body of a TypeScript double-quoted literal. */
    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code in 0x20..0x7E -> sb.append(ch)
                // A byte the client renders through its cp1252 table decodes to
                // a character outside latin-1, so the escape has to be wide
                // enough to carry it back unchanged.
                ch.code <= 0xFF -> sb.append("\\x").append("%02x".format(ch.code))
                else -> sb.append("\\u").append("%04x".format(ch.code))
            }
        }
        return sb.toString()
    }

    /** Inverse of [escape]. Input is the literal body, without the surrounding quotes. */
    fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != '\\') {
                sb.append(ch)
                i++
                continue
            }
            i++
            require(i < value.length) { "Dangling escape in string literal" }
            when (val esc = value[i++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '\'' -> sb.append('\'')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                '0' -> sb.append(0.toChar())
                'x' -> {
                    require(i + 2 <= value.length) { "Truncated \\x escape" }
                    sb.append(value.substring(i, i + 2).toInt(16).toChar())
                    i += 2
                }
                'u' -> {
                    require(i + 4 <= value.length) { "Truncated \\u escape" }
                    sb.append(value.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }
                else -> error("Unsupported escape \\$esc in string literal")
            }
        }
        return sb.toString()
    }
}
