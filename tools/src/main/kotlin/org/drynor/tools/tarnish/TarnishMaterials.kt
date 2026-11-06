package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.OsrsByteReader
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Locale

/** Read-only material discovery. Matching names identify reuse candidates, not gameplay parity. */
object TarnishMaterials {
    private data class SemanticAlias(val targetId: Int, val targetName: String, val evidence: String)
    private val semanticAliases = mapOf(
        "Mushroom" to SemanticAlias(6004, "Bittercap mushroom",
            "Tarnish items-json/6004.json examines this as a Bittercap Mushroom; Drynor ProduceType.Bittercap yields item 6004."),
        "Topiary hedge (bagged)" to SemanticAlias(8443, "Topiary hedge",
            "Drynor HouseObjects.TOPIARYHEDGE consumes item 8443; item 8206 is the furniture item and is not the bagged material."),
    )
    private data class Requirement(val name: String, val scope: String)
    private val requirements = listOf(
        Requirement("Limestone brick", "pool"), Requirement("Bucket of water", "pool"),
        Requirement("Soul rune", "pool"), Requirement("Body rune", "pool"),
        Requirement("Stamina potion(4)", "pool"), Requirement("Prayer potion(4)", "pool"),
        Requirement("Super restore(4)", "pool"), Requirement("Marble block", "pool/obelisk/seating/fence"),
        Requirement("Anti-venom(4)", "pool"), Requirement("Gold leaf", "pool"), Requirement("Blood rune", "pool"),
        Requirement("Spirit sapling", "teleport"), Requirement("Mushroom", "teleport/theme"),
        Requirement("Fairy enchantment", "teleport"), Requirement("Ancient crystal", "obelisk"),
        Requirement("Watering can(1)", "planting tool"), Requirement("Hammer", "tool"), Requirement("Saw", "tool"),
        Requirement("Redwood logs", "fence"), Requirement("Steel bar", "fence"),
        Requirement("Toktz-mej-tal", "fence"), Requirement("Tzhaar-ket-om", "fence"), Requirement("Toktz-xil-ul", "fence"),
        Requirement("Bucket of sand", "theme"), Requirement("Pink dye", "theme"), Requirement("Bagged nice tree", "theme"),
        Requirement("Supercompost", "theme"), Requirement("Blue dye", "theme"), Requirement("Magic secateurs", "theme/topiary tool"),
        Requirement("Granite (5kg)", "theme"), Requirement("Onyx", "theme/seating"),
        Requirement("Fire rune", "theme/seating"), Requirement("Lava rune", "theme/seating"),
        Requirement("Topiary hedge (bagged)", "topiary"), Requirement("Secateurs", "topiary tool"),
        Requirement("Teak plank", "seating"), Requirement("Mahogany plank", "seating"),
    )

    fun audit(source: Path, target: Path, output: Path) {
        require(!Files.exists(output)) { "Report already exists: $output" }
        val sourceFailures = mutableListOf<JsonObject>()
        val targetFailures = mutableListOf<JsonObject>()
        val nativeByName = mutableMapOf<String, MutableList<ItemDefinition>>()
        val nativeById = mutableMapOf<Int, ItemDefinition>()
        var nativeCount = 0
        FileCache(target.toString(), writable = false).use { native ->
            val decoder = ItemDecoder()
            for (archive in native.archiveIds(Index.ITEMS)) for (file in native.files(Index.ITEMS, archive)) {
                val id = (archive shl 8) or file
                try {
                    val definition = ItemDefinition(id)
                    decoder.readLoop(definition, BufferReader(requireNotNull(native.data(Index.ITEMS, archive, file))))
                    nativeCount++
                    nativeById[id] = definition
                    if (definition.notedTemplateId == -1 && definition.name != "null")
                        nativeByName.getOrPut(key(definition.name)) { mutableListOf() }.add(definition)
                } catch (failure: Exception) {
                    targetFailures += failureReport(id, failure)
                }
            }
        }
        TarnishCache(source).use { donor ->
            val raw = itemRecords(donor)
            val sourceItems = raw.mapIndexedNotNull { id, bytes ->
                try { parseItem(id, bytes) } catch (failure: Exception) {
                    sourceFailures += failureReport(id, failure)
                    null
                }
            }.associateBy { it.id }
            val sourceByName = sourceItems.values.filter { it.noteTemplate == -1 && it.placeholderTemplate == -1 && it.name != "null" }
                .groupBy { key(it.name) }
            val missingRoots = sortedSetOf<Int>()
            val records = requirements.map { requirement ->
                val alias = semanticAliases[requirement.name]
                val aliasTarget = alias?.let { candidate -> nativeById[candidate.targetId]?.takeIf {
                    it.notedTemplateId == -1 && key(it.name) == key(candidate.targetName)
                } }
                val targetItems = (nativeByName[key(requirement.name)].orEmpty() + listOfNotNull(aliasTarget)).distinctBy { it.id }.sortedBy { it.id }
                val sourceMatches = (sourceByName[key(requirement.name)].orEmpty() +
                    if (requirement.name == "Topiary hedge (bagged)") listOfNotNull(sourceItems[8443]?.takeIf { it.name == "Topiary hedge" }) else emptyList())
                    .distinctBy { it.id }.sortedBy { it.id }
                if (targetItems.isEmpty()) missingRoots.addAll(sourceMatches.map { it.id })
                buildJsonObject {
                    put("name", requirement.name); put("scope", requirement.scope)
                    if (aliasTarget != null) put("nativeSemanticAliasEvidence", alias!!.evidence)
                    put("status", when {
                        aliasTarget != null -> "native-semantic-alias-needs-recipe-review"
                        targetItems.isNotEmpty() -> "native-name-match-needs-semantic-review"
                        sourceMatches.isNotEmpty() -> "missing-native-import-candidate"
                        else -> "not-found-by-exact-name"
                    })
                    put("targetCandidates", JsonArray(targetItems.map { item -> buildJsonObject {
                        put("id", item.id); put("name", item.name); put("modelId", item.modelId); put("stackable", item.stackable)
                        put("inventoryOptions", JsonArray(item.options.map { it?.let(::JsonPrimitive) ?: JsonNull }))
                    } }))
                    put("sourceCandidates", JsonArray(sourceMatches.map { item -> item.report(raw[item.id]) }))
                }
            }
            val closure = sortedSetOf<Int>()
            val queue = ArrayDeque(missingRoots)
            val models = sortedSetOf<Int>()
            val dependencyRecords = mutableListOf<JsonObject>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!closure.add(id)) continue
                require(closure.size <= 10000) { "Unexpected material dependency expansion" }
                val item = sourceItems[id]
                if (item == null) {
                    dependencyRecords += buildJsonObject { put("sourceId", id); put("status", "missing-or-unparsed-source-item") }
                    continue
                }
                models.addAll(item.models)
                queue.addAll(item.links.filter { it !in closure })
                dependencyRecords += item.report(raw[id])
            }
            val modelRecords = models.map { id -> buildJsonObject {
                put("sourceId", id)
                try {
                    val bytes = donor.model(id)
                    val converted = TarnishModels.nativeBytes(bytes)
                    val model = ModelDecoder.read(id, converted)
                    check(ModelEncoder.encode(model).contentEquals(converted)) { "Converted native round trip changed bytes" }
                    put("sha256", TarnishGarden.sha(bytes)); put("bytes", bytes.size)
                    put("vertices", model.vertexCount); put("faces", model.faceCount)
                    put("materials", ints((model.faceTextures ?: shortArrayOf()).map { it.toInt() }.filter { it >= 0 }.toSet()))
                    put("nativeModelRoundTrip", true)
                } catch (failure: Exception) {
                    put("nativeModelRoundTrip", false); put("blocked", failure.message ?: failure.javaClass.simpleName)
                }
            } }
            val report = buildJsonObject {
                put("schemaVersion", 1); put("readOnly", true); put("scopeComplete", sourceFailures.isEmpty() && targetFailures.isEmpty())
                put("sourceCache", source.toRealPath().toString()); put("targetCache", target.toRealPath().toString())
                put("sourceItemRecords", raw.size); put("parsedSourceItems", sourceItems.size); put("parsedNativeItems", nativeCount)
                put("matchingPolicy", "Case-insensitive names of unnoted definitions; only whitespace before numeric dose parentheses is normalized. Do not equate source and destination IDs. Name matches still require gameplay/recipe parity review.")
                put("sourceClientOverridesApplied", false)
                put("requirements", JsonArray(records)); put("sourceParseFailures", JsonArray(sourceFailures)); put("targetParseFailures", JsonArray(targetFailures))
                put("missingNativeSourceRoots", ints(missingRoots)); put("sourceDependencyClosure", JsonArray(dependencyRecords)); put("sourceModels", JsonArray(modelRecords))
                put("readyForImport", false)
            }
            Files.createDirectories(output.toAbsolutePath().parent)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report), CREATE_NEW)
            println("Superior Garden materials: ${requirements.size} names; ${records.count { it["status"]!!.jsonPrimitive.content == "missing-native-import-candidate" }} missing native material names; report=$output")
        }
    }

    private data class SourceItem(
        val id: Int, val name: String, val models: Set<Int>, val links: Set<Int>, val noteTemplate: Int, val placeholderTemplate: Int,
    ) {
        fun report(bytes: ByteArray) = buildJsonObject {
            put("sourceId", id); put("name", name); put("sha256", TarnishGarden.sha(bytes))
            put("modelIds", ints(models)); put("linkedItemIds", ints(links))
            put("noteTemplate", noteTemplate); put("placeholderTemplate", placeholderTemplate)
        }
    }

    /** Tarnish ItemDefinition.readValues grammar; no unknown opcode is skipped. */
    private fun parseItem(id: Int, bytes: ByteArray): SourceItem {
        val input = OsrsByteReader(bytes)
        var name = "null"; var noteTemplate = -1; var placeholderTemplate = -1
        val models = sortedSetOf<Int>(); val links = sortedSetOf<Int>()
        fun ref(set: MutableSet<Int>) { input.u16().takeUnless { it == 65535 }?.let(set::add) }
        while (true) when (val opcode = input.u8()) {
            0 -> { input.requireEnd(); return SourceItem(id, name, models, links, noteTemplate, placeholderTemplate) }
            1, 24, 26, 78, 79, 90, 91, 92, 93 -> ref(models)
            2 -> name = input.string()
            3, 9, in 30..39 -> input.string()
            4, 5, 6, 7, 8, 10, 75, 94, 95, 110, 111, 112 -> input.skip(2)
            11, 16, 65 -> Unit
            12 -> input.skip(4)
            13, 14, 27, 42, 113, 114, 115 -> input.skip(1)
            23, 25 -> { ref(models); input.skip(1) }
            40, 41 -> input.skip(input.u8() * 4)
            97, 139, 140, 148 -> ref(links)
            98 -> { noteTemplate = input.u16().takeUnless { it == 65535 } ?: -1; if (noteTemplate >= 0) links.add(noteTemplate) }
            149 -> { placeholderTemplate = input.u16().takeUnless { it == 65535 } ?: -1; if (placeholderTemplate >= 0) links.add(placeholderTemplate) }
            in 100..109 -> { ref(links); input.skip(2) }
            249 -> repeat(input.u8()) {
                val type = input.u8(); require(type in 0..1); input.skip(3)
                if (type == 1) input.string() else input.skip(4)
            }
            else -> error("Unsupported Tarnish item opcode $opcode at ${input.position - 1}")
        }
    }

    internal fun itemRecords(cache: TarnishCache): List<ByteArray> {
        val files = cache.archive(2)
        val dat = requireNotNull(files[TarnishCache.hash("obj.dat")])
        val idx = ByteBuffer.wrap(requireNotNull(files[TarnishCache.hash("obj.idx")]))
        val count = idx.short.toInt() and 65535
        var offset = 0
        val records = ArrayList<ByteArray>(count)
        repeat(count) {
            require(idx.remaining() >= 2) { "Truncated item index" }
            val size = idx.short.toInt() and 65535
            require(size != 65535 && size <= dat.size - offset) { "Invalid item record size at $it" }
            records.add(dat.copyOfRange(offset, offset + size)); offset += size
        }
        // Some Tarnish repacks use the header as the highest physical ID, as loc.idx does.
        if (offset < dat.size && idx.remaining() >= 2) {
            val size = idx.short.toInt() and 65535
            require(size != 65535 && size == dat.size - offset) { "Invalid final indexed item" }
            records.add(dat.copyOfRange(offset, offset + size)); offset += size
        }
        require(offset == dat.size) { "Unindexed item bytes: $offset/${dat.size}" }
        return records
    }

    private fun key(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+\\((\\d+)\\)"), "($1)")
    private fun ints(values: Iterable<Int>) = JsonArray(values.map(::JsonPrimitive))
    private fun failureReport(id: Int, failure: Exception) = buildJsonObject {
        put("id", id); put("error", failure.message ?: failure.javaClass.simpleName)
    }
}
