package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.cache.FileCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Offline canonical mutation, accepting only the original cache and explicitly applied additions. */
object TarnishIncrementalImport {
    fun apply(output: Path, backup: Path, priorPackages: List<Path>, expectedStatus: String) {
        require(priorPackages.isNotEmpty()) { "Explicit prior-package chain required" }
        require(!Files.exists(output.resolve("applied.json"))) { "Package already applied" }
        val manifest = readManifest(output)
        require(manifest.getValue("status").jsonPrimitive.content == expectedStatus)
        val target = Path.of(manifest.getValue("target").jsonPrimitive.content).toRealPath()
        val assets = loadAssets(output, manifest)
        require(assets.isNotEmpty())
        require(assets.all { it.index in TarnishAnimatedAssets.allowedIndices(expectedStatus) }) { "Unreviewed import index" }
        TarnishAnimatedAssets.verify(manifest, assets)
        val previousGroups = mutableMapOf<Pair<Int, Int>, MutableList<InfernoAssetWriter.Asset>>()
        val chain = priorPackages.map { path ->
            require(Files.isRegularFile(path.resolve("applied.json"))) { "Prior package lacks application evidence: $path" }
            val previous = readManifest(path)
            require(Path.of(previous.getValue("target").jsonPrimitive.content).toRealPath() == target)
            loadAssets(path, previous).also { staged ->
                staged.forEach { previousGroups.getOrPut(it.index to it.archive) { mutableListOf() }.add(it) }
            }
        }
        val previous = chain.flatten()
        require(previous.map { Triple(it.index, it.archive, it.file) }.distinct().size == previous.size) { "Prior packages overlap" }
        val baseline = Json.parseToJsonElement(Files.readString(backup.resolve("cache-baseline.json"))).jsonArray
        baseline.forEach { value ->
            val entry = value.jsonObject
            val name = entry.getValue("name").jsonPrimitive.content
            require(name == Path.of(name).fileName.toString())
            check(TarnishGarden.sha(Files.readAllBytes(backup.resolve("cache").resolve(name))) == entry.getValue("sha256").jsonPrimitive.content) { "Rollback backup changed: $name" }
        }
        val changed = assets.map { it.index to it.archive }.toSet()
        val unchanged = linkedMapOf<Pair<Int, Int>, String>()
        val siblings = linkedMapOf<Triple<Int, Int, Int>, String>()
        FileCache(backup.resolve("cache").toString(), writable = false).use { original ->
            FileCache(target.toString(), writable = false).use { current ->
                for (index in original.indices().toSet() + current.indices().toSet()) {
                    val oldIds = original.archiveIds(index).toSet()
                    val nowIds = current.archiveIds(index).toSet()
                    check(nowIds == oldIds + previousGroups.keys.filter { it.first == index }.map { it.second }) { "Unexpected archives in index $index" }
                    for (archive in oldIds) {
                        val additions = previousGroups[index to archive]
                        if (additions == null) {
                            check(TarnishGarden.sha(requireNotNull(original.container(index, archive))) == TarnishGarden.sha(requireNotNull(current.container(index, archive)))) { "Unplanned baseline change: $index/$archive" }
                        } else {
                            val oldFiles = original.files(index, archive).toSet()
                            check(current.files(index, archive).toSet() == oldFiles + additions.map { it.file }) { "Unexpected prior sibling files: $index/$archive" }
                            oldFiles.forEach { file -> check(requireNotNull(original.data(index, archive, file)).contentEquals(requireNotNull(current.data(index, archive, file)))) { "Original sibling changed: $index/$archive/$file" } }
                        }
                    }
                    for (archive in nowIds - oldIds) {
                        val additions = requireNotNull(previousGroups[index to archive])
                        check(current.files(index, archive).toSet() == additions.map { it.file }.toSet()) { "Unexpected files in prior new archive: $index/$archive" }
                    }
                    for (archive in nowIds) {
                        if (index to archive in changed) for (file in current.files(index, archive)) siblings[Triple(index, archive, file)] = TarnishGarden.sha(requireNotNull(current.data(index, archive, file)))
                        else unchanged[index to archive] = TarnishGarden.sha(requireNotNull(current.container(index, archive)))
                    }
                }
                previous.forEach { check(requireNotNull(current.data(it.index, it.archive, it.file)).contentEquals(it.bytes)) { "Prior asset changed: ${it.index}/${it.archive}/${it.file}" } }
                assets.forEach { check(current.data(it.index, it.archive, it.file) == null) { "Destination occupied: ${it.index}/${it.archive}/${it.file}" } }
                assets.filter { it.index == 0 || it.index == 1 }.forEach {
                    check(it.archive !in current.archiveIds(it.index)) { "Animated frame/base group must be newly allocated" }
                }
            }
        }
        fun verifyPreserved() = FileCache(target.toString(), writable = false).use { current ->
            unchanged.forEach { (address, hash) -> check(TarnishGarden.sha(requireNotNull(current.container(address.first, address.second))) == hash) { "Unrelated archive changed: $address" } }
            siblings.forEach { (address, hash) -> check(TarnishGarden.sha(requireNotNull(current.data(address.first, address.second, address.third))) == hash) { "Existing sibling changed: $address" } }
        }
        try {
            InfernoAssetWriter.write(target, assets)
            FileCache(target.toString(), writable = false).use { current -> assets.forEach { check(requireNotNull(current.data(it.index, it.archive, it.file)).contentEquals(it.bytes)) { "Imported asset readback failed" } } }
            verifyPreserved()
        } catch (failure: Exception) {
            baseline.forEach { value ->
                val name = value.jsonObject.getValue("name").jsonPrimitive.content
                Files.copy(backup.resolve("cache").resolve(name), target.resolve(name), REPLACE_EXISTING)
            }
            // Reapply in original order so shared archive versions also return to their prior values.
            chain.forEach { InfernoAssetWriter.write(target, it) }
            verifyPreserved()
            throw IllegalStateException("Import failed; original cache and audited prior packages restored", failure)
        }
        Files.writeString(output.resolve("applied.json"), buildJsonObject {
            put("status", "$expectedStatus-applied"); put("newAssets", assets.size)
            put("unchangedArchives", unchanged.size); put("preservedSiblingFiles", siblings.size)
            put("backup", backup.toRealPath().toString())
            put("priorPackages", JsonArray(priorPackages.map { JsonPrimitive(it.toRealPath().toString()) }))
        }.toString())
        println("Applied $expectedStatus: ${assets.size} assets; ${unchanged.size} unrelated archives and ${siblings.size} sibling files preserved.")
    }

    private fun readManifest(root: Path) = Json.parseToJsonElement(Files.readString(root.resolve("manifest.json"))).jsonObject
    private fun loadAssets(root: Path, manifest: JsonObject) = manifest.getValue("assets").jsonArray.map { value ->
        val entry = value.jsonObject
        val index = entry.getValue("index").jsonPrimitive.int; val archive = entry.getValue("archive").jsonPrimitive.int; val file = entry.getValue("file").jsonPrimitive.int
        require(index in 0..254 && archive >= 0 && file >= 0)
        val bytes = Files.readAllBytes(root.resolve("assets/$index/$archive/$file.bin"))
        check(TarnishGarden.sha(bytes) == entry.getValue("sha256").jsonPrimitive.content) { "Staged asset changed: $index/$archive/$file" }
        InfernoAssetWriter.Asset(index, archive, file, bytes)
    }.also { assets -> require(assets.map { Triple(it.index, it.archive, it.file) }.distinct().size == assets.size) }
}
