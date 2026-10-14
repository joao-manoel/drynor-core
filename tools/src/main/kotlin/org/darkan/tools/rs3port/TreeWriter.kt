package org.darkan.tools.rs3port

import world.gregs.voidps.cache.source.codec.SourceFile
import java.nio.file.Files
import java.nio.file.Path

class TreeWriter(private val tree: Path) {

    private val pending = ArrayList<SourceFile>()

    fun add(converted: Converted) {
        pending.addAll(converted.files)
    }

    fun add(files: List<SourceFile>) {
        pending.addAll(files)
    }

    fun flush(): List<Pair<String, String>> = pending.map { file ->
        val path = tree.resolve(file.path)
        val state = when {
            !Files.exists(path) -> "new"
            Files.readAllBytes(path).contentEquals(file.bytes) -> "same"
            else -> "changed"
        }
        if (state != "same") {
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes)
        }
        file.path to state
    }
}

class GrammarFindings {

    private val messages = ArrayList<String>()

    private var records = 0

    fun check(record: String, found: List<String>) {
        records++
        found.forEach { messages.add("$record: $it") }
    }

    fun refuse() {
        if (messages.isNotEmpty()) {
            messages.forEach { println("  client grammar: $it") }
            throw IllegalStateException("The 727 client grammar rejects ${messages.size} findings in $records records; nothing was written.")
        }
        println("client grammar: $records records re-read, no findings")
    }
}
