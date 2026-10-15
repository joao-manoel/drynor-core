package org.drynor.tools.rs3port

import java.nio.file.Files
import java.nio.file.Path

object SourceEdits {

    const val GZIP = "{\"compression\": \"gzip\"}"

    const val VERSIONED_GZIP = "{\"version\": 1, \"compression\": \"gzip\"}"

    fun named(hash: Int): String = "{\"compression\": \"gzip\", \"nameHash\": $hash}"

    fun modelIndex(tree: Path, ids: List<Int>): Int = archiveIndex(tree, "models", ids.associateWith { GZIP })

    fun archiveIndex(tree: Path, directory: String, entries: Map<Int, String>): Int {
        val path = tree.resolve(directory).resolve("index.json")
        val lines = ArrayList(Files.readAllLines(path))
        val present = HashSet<Int>()
        var last = -1
        for ((index, line) in lines.withIndex()) {
            val id = archiveId(line) ?: continue
            present.add(id)
            last = index
        }
        check(last >= 0) { "$directory/index.json has no archives." }
        val missing = entries.keys.filter { it !in present }.sorted()
        if (missing.isEmpty()) {
            return 0
        }
        check(missing.first() > present.max()) {
            "$directory ${missing.first()} is below the highest archive ${present.max()}; ids only ever append."
        }
        lines[last] = lines[last] + ","
        val indent = lines[last].takeWhile { it == ' ' }
        val added = missing.mapIndexed { index, id ->
            "$indent\"$id\": ${entries.getValue(id)}" + if (index == missing.size - 1) "" else ","
        }
        lines.addAll(last + 1, added)
        Files.writeString(path, lines.joinToString("\n") + "\n")
        return missing.size
    }

    fun renameArchive(tree: Path, directory: String, id: Int, hash: Int) {
        val path = tree.resolve(directory).resolve("index.json")
        val lines = ArrayList(Files.readAllLines(path))
        val index = lines.indexOfFirst { archiveId(it) == id }
        check(index >= 0) { "$directory/index.json has no archive $id." }
        val pattern = Regex("(\"nameHash\": )-?\\d+")
        check(pattern.containsMatchIn(lines[index])) { "$directory archive $id carries no name hash: ${lines[index]}" }
        lines[index] = lines[index].replace(pattern, "$1$hash")
        Files.writeString(path, lines.joinToString("\n") + "\n")
    }

    fun renameEntry(tree: Path, type: String, namespace: String, id: Int, from: String, to: String) {
        val catalog = tree.resolve("gamevals").resolve("$type.json")
        val entry = "\"$id\": \"$from\""
        val text = Files.readString(catalog)
        check(text.split(entry).size == 2) { "gamevals/$type.json does not hold $entry exactly once." }
        check(!text.contains(": \"$to\"")) { "gamevals/$type.json already names another id $to." }
        Files.writeString(catalog, text.replace(entry, "\"$id\": \"$to\""))

        val declarations = tree.resolve("clientscripts").resolve("gamevals.d.ts")
        val lines = ArrayList(Files.readAllLines(declarations))
        val start = lines.indexOfFirst { it.startsWith("declare namespace $namespace {") }
        check(start >= 0) { "gamevals.d.ts has no $namespace namespace." }
        val declaration = "    /** $type $id */ const $from: number;"
        val index = lines.subList(start, lines.size).indexOf(declaration)
        check(index >= 0) { "the $namespace namespace does not declare $from as $type $id." }
        lines[start + index] = "    /** $type $id */ const $to: number;"
        Files.writeString(declarations, lines.joinToString("\n") + "\n")
    }

    private fun archiveId(line: String): Int? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("\"") || !trimmed.contains("{")) {
            return null
        }
        return trimmed.substringAfter('"').substringBefore('"').toIntOrNull()
    }

    fun catalog(tree: Path, type: String, names: Map<Int, String>, channel: String? = null): Int {
        val path = tree.resolve("gamevals").resolve("$type.json")
        val text = Files.readString(path)
        val entries = Regex("\"(\\d+)\": \"([^\"]*)\"").findAll(text.substringAfter("\"entries\""))
        val present = entries.map { it.groupValues[1].toInt() }.toSet()
        val missing = names.keys.filter { it !in present }.sorted()
        if (missing.isEmpty()) {
            return 0
        }
        check(missing.first() > present.max()) {
            "$type ${missing.first()} is below the highest named id ${present.max()}; ids only ever append."
        }
        val closing = text.lastIndexOf("\n  }")
        check(closing > 0) { "gamevals/$type.json has no entry block to append to." }
        val added = missing.joinToString("") { ",\n    \"$it\": \"${names.getValue(it)}\"" }
        var bumped = bump(text.substring(0, closing) + added, listOf("cache_ids", "named"), missing.size, 0)
        if (channel != null) {
            val block = bumped.indexOf("\"by_channel\"")
            check(block >= 0) { "gamevals/$type.json has no by_channel block for channel $channel." }
            bumped = bump(bumped, listOf(channel), missing.size, block)
        }
        Files.writeString(path, bumped + text.substring(closing))
        return missing.size
    }

    private fun bump(text: String, fields: List<String>, added: Int, from: Int): String {
        var out = text
        for (field in fields) {
            val match = Regex("(\"$field\": )(\\d+)").find(out, from) ?: continue
            out = out.substring(0, match.range.first) +
                match.groupValues[1] + (match.groupValues[2].toInt() + added) +
                out.substring(match.range.last + 1)
        }
        return out
    }

    fun declarations(tree: Path, namespace: String, type: String, names: Map<Int, String>): Int {
        val path = tree.resolve("clientscripts").resolve("gamevals.d.ts")
        val lines = ArrayList(Files.readAllLines(path))
        val start = lines.indexOfFirst { it.startsWith("declare namespace $namespace {") }
        check(start >= 0) { "gamevals.d.ts has no $namespace namespace." }
        var end = start
        while (end < lines.size && lines[end] != "}") {
            end++
        }
        check(end < lines.size) { "the $namespace namespace is not closed." }
        val present = HashSet<Int>()
        for (index in start + 1 until end) {
            val id = Regex("/\\*\\* $type (\\d+) \\*/").find(lines[index])?.groupValues?.get(1)?.toIntOrNull()
            if (id != null) {
                present.add(id)
            }
        }
        val missing = names.keys.filter { it !in present }.sorted()
        if (missing.isEmpty()) {
            return 0
        }
        lines.addAll(end, missing.map { "    /** $type $it */ const ${names.getValue(it)}: number;" })
        val header = start - 1
        val counts = Regex("\\((\\d+) named of (\\d+)\\)").find(lines[header])
        if (counts != null) {
            lines[header] = lines[header].replaceRange(
                counts.range,
                "(${counts.groupValues[1].toInt() + missing.size} named of ${counts.groupValues[2].toInt() + missing.size})"
            )
        }
        Files.writeString(path, lines.joinToString("\n") + "\n")
        return missing.size
    }

    fun enumRows(tree: Path, id: Int, rows: Map<Int, Int>): Int {
        val path = tree.resolve("config_enum").resolve("$id.json")
        val text = Files.readString(path)
        val map = text.substringAfter("\"map\": {").substringBefore("\n  }")
        val present = Regex("\"(\\d+)\": (-?\\d+)").findAll(map).associate {
            it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        val missing = rows.filterKeys { it !in present }.toSortedMap()
        if (missing.isEmpty()) {
            return 0
        }
        check(missing.firstKey() > present.keys.max()) {
            "enum $id row ${missing.firstKey()} is below the highest row ${present.keys.max()}; rows only ever append."
        }
        val closing = text.indexOf("\n  }", text.indexOf("\"map\": {"))
        val added = missing.entries.joinToString("") { ",\n    \"${it.key}\": ${it.value}" }
        var out = text.substring(0, closing) + added + text.substring(closing)
        val length = (present.keys + missing.keys).max() + 1
        for (field in listOf("length", "arraySize")) {
            out = out.replace(Regex("(\"$field\": )\\d+"), "$1$length")
        }
        Files.writeString(path, out)
        return missing.size
    }
}
