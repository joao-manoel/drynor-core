package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.cs2.Cs2Codec
import org.drynor.cs2.Cs2Op
import org.drynor.cs2.Cs2Script
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Appends a native room row, preserving all old component addresses and saved room ordinals. */
object TarnishGardenRoomUi {
    private const val STATUS = "garden-room-ui-native-readback"
    private val json = Json { prettyPrint = true }

    fun prepare(target: Path, output: Path) {
        require(!Files.exists(output)) { "Output already exists: $output" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        var originalScriptHash = ""
        FileCache(target.toString(), writable = false).use { cache ->
            check(cache.lastFileId(Index.INTERFACES, 402) == 117) { "Room menu is not the audited 118-component layout" }
            fun source(id: Int) = decode(id, requireNotNull(cache.data(Index.INTERFACES, 402, id)))
            check(source(61).text == "Formal garden: Lvl 55")
            check(source(108).name == "Formal garden" && source(108).options.contentEquals(arrayOf("Build")))
            check(source(117).onLoadScript?.script == 2686)
            val components = listOf(
                source(108).copy(id = (402 shl 16) or 118, basePositionY = 1403, name = "Superior garden"),
                source(61).copy(id = (402 shl 16) or 119, basePositionY = 1415, text = "Superior garden: Lvl 65"),
                source(83).copy(id = (402 shl 16) or 120, basePositionY = 1425, text = "75000 coins"),
                source(28).copy(id = (402 shl 16) or 121, basePositionY = 1408),
            )
            components.forEach { component ->
                val bytes = encode(component)
                val readBack = decode(component.id and 0xffff, bytes)
                check(readBack.parent == 12 && readBack.basePositionY == component.basePositionY)
                check(readBack.text == component.text && readBack.name == component.name)
                check(readBack.options.contentEquals(component.options))
                assets += InfernoAssetWriter.Asset(Index.INTERFACES, 402, component.id and 0xffff, bytes)
            }
            val original = requireNotNull(cache.data(Index.CLIENT_SCRIPTS, 2686, 0))
            originalScriptHash = TarnishGarden.sha(original)
            val script = Cs2Codec.decode(original)
            check(Cs2Codec.encode(script).contentEquals(original))
            check(script.instructions.size == 14 && script.instructions[0].intOperand == 332)
            check(script.instructions[1].op == Cs2Op.PUSH_CONSTANT_INT && script.instructions[1].intOperand == 1404)
            check(script.instructions[2].intOperand == ((402 shl 16) or 12) && script.instructions[3].op == Cs2Op.IF_SETSCROLLSIZE)
            val instructions = script.instructions.toMutableList().also { it[1] = it[1].copy(intOperand = 1468) }
            val patched = Cs2Script(script.name, instructions, script.intLocalsCount, script.stringLocalsCount,
                script.longLocalsCount, script.intArgsCount, script.stringArgsCount, script.longArgsCount, script.switchTables)
            val bytes = Cs2Codec.encode(patched)
            check(Cs2Codec.decode(bytes).instructions == instructions)
            assets += InfernoAssetWriter.Asset(Index.CLIENT_SCRIPTS, 2686, 0, bytes, replaceExisting = true)
        }
        assets.forEach { asset ->
            val file = output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(file.parent)
            Files.write(file, asset.bytes)
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("status", STATUS); put("target", target.toRealPath().toString())
            put("interface", 402); put("selectComponent", 118); put("priceComponent", 120)
            put("originalScrollScriptSha256", originalScriptHash)
            put("scrollHeight", 1468)
            put("assets", JsonArray(assets.map { a -> buildJsonObject {
                put("index", a.index); put("archive", a.archive); put("file", a.file)
                put("replaceExisting", a.replaceExisting); put("sha256", TarnishGarden.sha(a.bytes))
            } }))
        }
        Files.writeString(output.resolve("manifest.json"), json.encodeToString(JsonObject.serializer(), manifest))
        println("Prepared native room menu row 402:118..121 and bounded scroll script 2686. No cache changed.")
    }

    fun apply(output: Path, backup: Path, priorPackages: List<Path>) {
        require(priorPackages.isNotEmpty()) { "Explicit prior-package chain required" }
        require(!Files.exists(output.resolve("applied.json")))
        val manifest = manifest(output)
        require(manifest.getValue("status").jsonPrimitive.content == STATUS)
        val target = Path.of(manifest.getValue("target").jsonPrimitive.content).toRealPath()
        val assets = assets(output)
        check(assets.map { Triple(it.index, it.archive, it.file) }.toSet() ==
            (118..121).map { Triple(Index.INTERFACES, 402, it) }.toSet() + Triple(Index.CLIENT_SCRIPTS, 2686, 0))
        check(assets.count { it.replaceExisting } == 1 && assets.single { it.replaceExisting }.index == Index.CLIENT_SCRIPTS)
        val chain = priorPackages.map { root ->
            require(Files.isRegularFile(root.resolve("applied.json"))) { "Prior package was not applied: $root" }
            require(Path.of(manifest(root).getValue("target").jsonPrimitive.content).toRealPath() == target)
            assets(root)
        }
        val previous = chain.flatten()
        check(previous.none { it.index == Index.INTERFACES || it.index == Index.CLIENT_SCRIPTS })
        val previousGroups = previous.groupBy { it.index to it.archive }
        val baseline = Json.parseToJsonElement(Files.readString(backup.resolve("cache-baseline.json"))).jsonArray
        baseline.forEach { record ->
            val entry = record.jsonObject
            val name = entry.getValue("name").jsonPrimitive.content
            require(Path.of(name).fileName.toString() == name)
            check(TarnishGarden.sha(Files.readAllBytes(backup.resolve("cache").resolve(name))) == entry.getValue("sha256").jsonPrimitive.content)
        }
        val changed = assets.map { it.index to it.archive }.toSet()
        val unchanged = linkedMapOf<Pair<Int, Int>, String>()
        val siblings = linkedMapOf<Triple<Int, Int, Int>, String>()
        val replacements = assets.filter { it.replaceExisting }.map { Triple(it.index, it.archive, it.file) }.toSet()
        FileCache(backup.resolve("cache").toString(), writable = false).use { original ->
            FileCache(target.toString(), writable = false).use { current ->
                for (index in original.indices().toSet() + current.indices().toSet()) {
                    val oldIds = original.archiveIds(index).toSet()
                    val currentIds = current.archiveIds(index).toSet()
                    check(currentIds == oldIds + previousGroups.keys.filter { it.first == index }.map { it.second })
                    for (archive in oldIds) {
                        val staged = previousGroups[index to archive]
                        if (staged == null) check(requireNotNull(original.container(index, archive)).contentEquals(requireNotNull(current.container(index, archive))))
                        else {
                            check(current.files(index, archive).toSet() == original.files(index, archive).toSet() + staged.map { it.file })
                            for (file in original.files(index, archive)) check(requireNotNull(original.data(index, archive, file)).contentEquals(requireNotNull(current.data(index, archive, file))))
                        }
                    }
                    for (archive in currentIds - oldIds) check(current.files(index, archive).toSet() == previousGroups.getValue(index to archive).map { it.file }.toSet())
                    for (archive in currentIds) {
                        if (index to archive !in changed) unchanged[index to archive] = TarnishGarden.sha(requireNotNull(current.container(index, archive)))
                        else for (file in current.files(index, archive)) if (Triple(index, archive, file) !in replacements)
                            siblings[Triple(index, archive, file)] = TarnishGarden.sha(requireNotNull(current.data(index, archive, file)))
                    }
                }
                previous.forEach { check(requireNotNull(current.data(it.index, it.archive, it.file)).contentEquals(it.bytes)) }
                assets.forEach { check((current.data(it.index, it.archive, it.file) != null) == it.replaceExisting) }
                check(TarnishGarden.sha(requireNotNull(current.data(Index.CLIENT_SCRIPTS, 2686, 0))) == manifest.getValue("originalScrollScriptSha256").jsonPrimitive.content)
            }
        }
        fun verify() = FileCache(target.toString(), writable = false).use { current ->
            unchanged.forEach { (key, hash) -> check(TarnishGarden.sha(requireNotNull(current.container(key.first, key.second))) == hash) }
            siblings.forEach { (key, hash) -> check(TarnishGarden.sha(requireNotNull(current.data(key.first, key.second, key.third))) == hash) }
            assets.forEach { check(requireNotNull(current.data(it.index, it.archive, it.file)).contentEquals(it.bytes)) }
            check(decode(119, requireNotNull(current.data(Index.INTERFACES, 402, 119))).text == "Superior garden: Lvl 65")
            check(Cs2Codec.decode(requireNotNull(current.data(Index.CLIENT_SCRIPTS, 2686, 0))).instructions[1].intOperand == 1468)
        }
        try {
            InfernoAssetWriter.write(target, assets)
            verify()
        } catch (failure: Exception) {
            baseline.forEach { record ->
                val name = record.jsonObject.getValue("name").jsonPrimitive.content
                Files.copy(backup.resolve("cache").resolve(name), target.resolve(name), REPLACE_EXISTING)
            }
            chain.forEach { InfernoAssetWriter.write(target, it) }
            throw IllegalStateException("Room UI import failed; baseline and prior packages restored", failure)
        }
        Files.writeString(output.resolve("applied.json"), buildJsonObject {
            put("status", "$STATUS-applied"); put("newComponents", 4); put("boundedScriptReplacements", 1)
            put("unchangedArchives", unchanged.size); put("preservedSiblingFiles", siblings.size)
            put("backup", backup.toRealPath().toString())
            put("priorPackages", JsonArray(priorPackages.map { JsonPrimitive(it.toRealPath().toString()) }))
        }.toString())
        println("Applied native room row and verified packed components, scroll bytecode, ${unchanged.size} unrelated archives and ${siblings.size} siblings.")
    }

    private fun decode(id: Int, bytes: ByteArray): InterfaceComponentDefinition {
        val reader = BufferReader(bytes)
        return InterfaceComponentDefinition(id = (402 shl 16) or id).also {
            with(InterfaceDecoder()) { it.read(reader) }
            check(reader.position() == bytes.size) { "Interface component $id has trailing bytes" }
        }
    }

    private fun encode(component: InterfaceComponentDefinition) = BufferWriter(8192).apply { with(InterfaceEncoder()) { encode(component) } }.toArray()
    private fun manifest(root: Path) = Json.parseToJsonElement(Files.readString(root.resolve("manifest.json"))).jsonObject
    private fun assets(root: Path) = manifest(root).getValue("assets").jsonArray.map { value ->
        val record = value.jsonObject
        val index = record.getValue("index").jsonPrimitive.int
        val archive = record.getValue("archive").jsonPrimitive.int
        val file = record.getValue("file").jsonPrimitive.int
        val bytes = Files.readAllBytes(root.resolve("assets/$index/$archive/$file.bin"))
        check(TarnishGarden.sha(bytes) == record.getValue("sha256").jsonPrimitive.content)
        InfernoAssetWriter.Asset(index, archive, file, bytes, record["replaceExisting"]?.jsonPrimitive?.boolean ?: false)
    }.also { require(it.map { a -> Triple(a.index, a.archive, a.file) }.distinct().size == it.size) }
}
