package com.rs.cache

import java.nio.file.Files
import java.nio.file.Path

/** Read-only export for checking the actual Drynor client's CS2 parser against the packed cache. */
object ClientScriptFixtures {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "packed cache and fixture directory are required" }
        Cache.init(args[0])
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(output)
        val index = Cache.STORE.getIndex(IndexType.CS2_SCRIPTS)
        var count = 0
        for (id in 0..index.lastArchiveId) {
            val data = index.getFile(id) ?: continue
            Files.write(output.resolve("$id.cs2"), data)
            count++
        }
        println("Exported $count packed CS2 scripts for client parser validation")
    }
}
