package world.gregs.voidps.cache.source

/**
 * The little JSON the source tree writes by hand.
 *
 * `index.json` and `manifest.json` are written rather than serialised because their exact text
 * is part of the contract: re-unpacking an unchanged cache has to produce the same bytes or git
 * sees churn, and the builder compares stored metadata line by line. A serialiser's field order,
 * indentation and number formatting are all things it is free to change; these functions are not.
 * Reading goes the other way and is tolerant - `kotlinx.serialization` parses whatever is there.
 */
internal object SourceJson {

    /** [value] as a JSON string literal, quotes included. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character < ' ') {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /** `[a, b, c]` on one line. */
    fun array(values: IntArray): String = values.joinToString(", ", "[", "]")

    /** `["a", "b"]` on one line. */
    fun strings(values: List<String>): String = values.joinToString(", ", "[", "]") { quote(it) }

    /** `{"1": 2, "3": 4}` on one line, keys ascending numerically. */
    fun intMap(values: Map<Int, Int>): String =
        values.entries.sortedBy { it.key }.joinToString(", ", "{", "}") { "${quote(it.key.toString())}: ${it.value}" }

    /** `{"1": "a"}` on one line, keys ascending numerically. */
    fun stringMap(values: Map<Int, String>): String =
        values.entries.sortedBy { it.key }
            .joinToString(", ", "{", "}") { "${quote(it.key.toString())}: ${quote(it.value)}" }
}
