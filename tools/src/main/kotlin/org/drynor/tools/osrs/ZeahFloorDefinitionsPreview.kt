package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.decoder.UnderlayDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Stages audited high floor files in a fresh packed copy, without importing terrain or gameplay. */
object ZeahFloorDefinitionsPreview {
    fun build(sourcePath: Path, targetPath: Path, planPath: Path, outputPath: Path) {
        val sourceRoot = sourcePath.toRealPath()
        val targetRoot = targetPath.toRealPath()
        val output = outputPath.toAbsolutePath().normalize()
        require(sourceRoot != targetRoot && !output.startsWith(sourceRoot) && !output.startsWith(targetRoot))
        require(!Files.exists(output)) { "Preview output already exists: $output" }
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        require(plan.getValue("scopeComplete").jsonPrimitive.content == "false")
        require(Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath() == targetRoot)
        val assets = plan.getValue("floorDefinitionsToImport").jsonArray.map { it.jsonObject }
        require(assets.isNotEmpty())
        require(assets.map { it.getValue("configArchive").jsonPrimitive.int to it.getValue("fileId").jsonPrimitive.int }.distinct().size == assets.size)
        val sourceHashes = cacheHashes(sourceRoot)
        val targetHashes = cacheHashes(targetRoot)
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val payloads = assets.map { asset ->
                    val archive = asset.getValue("configArchive").jsonPrimitive.int
                    val file = asset.getValue("fileId").jsonPrimitive.int
                    require(archive == 1 || archive == 4)
                    require(file > 255) { "Low floor ID $file needs a separate collision review." }
                    check(target.data(Index.CONFIGS, archive, file) == null) { "Refusing overwrite of $archive/$file" }
                    val bytes = requireNotNull(source.data(Index.CONFIGS, archive, file))
                    check(sha256(bytes) == asset.getValue("sourceSha256").jsonPrimitive.content)
                    when (archive) {
                        4 -> {
                            val definition = OverlayDefinition(file)
                            OverlayDecoder().readLoop(definition, BufferReader(bytes))
                            require(definition.texture == -1) { "Overlay $file needs a texture dependency." }
                        }
                        1 -> {
                            val definition = UnderlayDefinition(file)
                            UnderlayDecoder().readLoop(definition, BufferReader(bytes))
                            require(definition.texture == -1) { "Underlay $file needs a texture dependency." }
                        }
                    }
                    Triple(archive, file, bytes)
                }
                Files.createDirectories(output)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(targetRoot).use { paths ->
                    paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it, packed.resolve(it.fileName)) }
                }
                FileCache(packed.toString(), writable = true).use { result ->
                    for ((archive, file, bytes) in payloads) result.write(Index.CONFIGS, archive, file, bytes)
                }
                var unchangedMapArchives = 0
                FileCache(packed.toString(), writable = false).use { result ->
                    for ((archive, file, bytes) in payloads) {
                        check(result.data(Index.CONFIGS, archive, file).contentEquals(bytes)) { "Readback failed: $archive/$file" }
                    }
                    for (archive in listOf(1, 4)) for (file in target.files(Index.CONFIGS, archive)) {
                        check(target.data(Index.CONFIGS, archive, file).contentEquals(result.data(Index.CONFIGS, archive, file))) {
                            "Existing floor changed: $archive/$file"
                        }
                    }
                    for (archive in target.archiveIds(Index.MAPS)) {
                        check(target.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive))) {
                            "Existing map archive changed: $archive"
                        }
                        unchangedMapArchives++
                    }
                }
                check(sourceHashes == cacheHashes(sourceRoot) && targetHashes == cacheHashes(targetRoot))
                val report = buildJsonObject {
                    put("status", "experimental-definitions-only")
                    put("packedCache", packed.toString())
                    put("importedOverlayDefinitions", payloads.count { it.first == 4 })
                    put("importedUnderlayDefinitions", payloads.count { it.first == 1 })
                    put("unchangedMapArchivesVerified", unchangedMapArchives)
                    put("existingFloorFilesPreserved", true)
                    put("sourceAndTargetCacheHashesPreserved", true)
                    put("clientVisualValidated", false)
                    put("terrainImported", false)
                    put("limitations", "Floor definitions only. No terrain, world-map area, objects, NPCs, instances or gameplay. Never use as a runtime cache.")
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n")
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cacheHashes(path: Path): Map<String, String> = Files.list(path).use { paths ->
        paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.sorted().toList().associate {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(it).use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            it.fileName.toString() to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
