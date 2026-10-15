package org.drynor.tools.rs3port

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.nio.file.Files
import java.nio.file.Path

class UrnImporter(
    private val rs3Tree: Path,
    private val sourceTree: Path,
    private val spec: UrnSpec,
    private val crosswalk: Crosswalk
) {

    private val roles = listOf("unfired", "cert_unfired", "norune", "cert_norune", "rune", "started", "full", "cert_full")

    private val templates = mapOf(
        "unfired" to 20379,
        "norune" to 20380,
        "cert_unfired" to 20381,
        "cert_norune" to 20381,
        "cert_full" to 20381,
        "rune" to 20382,
        "started" to 20383,
        "full" to 20384
    )

    private val certParent = mapOf("cert_unfired" to "unfired", "cert_norune" to "norune", "cert_full" to "full")

    private val objNames = LinkedHashMap<Int, String>()
    private val modelNames = LinkedHashMap<Int, String>()
    private val seqNames = LinkedHashMap<Int, String>()
    private val structNames = LinkedHashMap<Int, String>()
    private val enumRows = LinkedHashMap<Int, Int>()
    private val newModels = LinkedHashMap<Int, Int>()
    private val ids = LinkedHashMap<String, LinkedHashMap<Int, Int>>()

    fun run() {
        checkCeilings()
        for (set in spec.sets) {
            models(set)
            objs(set)
            seqs(set)
            struct(set)
        }
        SourceEdits.modelIndex(sourceTree, newModels.values.toList())
        val added = listOf(
            "obj" to SourceEdits.catalog(sourceTree, "obj", objNames),
            "model" to SourceEdits.catalog(sourceTree, "model", modelNames),
            "seq" to SourceEdits.catalog(sourceTree, "seq", seqNames),
            "struct" to SourceEdits.catalog(sourceTree, "struct", structNames)
        )
        SourceEdits.declarations(sourceTree, "Obj", "obj", objNames)
        SourceEdits.declarations(sourceTree, "Model", "model", modelNames)
        SourceEdits.declarations(sourceTree, "Seq", "seq", seqNames)
        SourceEdits.declarations(sourceTree, "Struct", "struct", structNames)
        SourceEdits.enumRows(sourceTree, SKILL_GUIDE_ENUM, enumRows)
        writeIdMap()
        println("sets ${spec.sets.size}, objs ${objNames.size}, models ${newModels.size}, seqs ${seqNames.size}, structs ${structNames.size}")
        println("catalog entries added: " + added.joinToString { "${it.first} ${it.second}" })
    }

    /**
     * The tree's highest id of each type is either the ceiling the allocation assumed or, on a
     * re-run, the last id the allocation used. Anything else means the index has grown since the
     * spec was written and the allocation would leave a hole or land on somebody else's id.
     */
    private fun checkCeilings() {
        for (type in listOf("obj", "seq", "struct", "model")) {
            val highest = highest(directory(type), suffix(type))
            check(highest == spec.ceilings.getValue(type) || highest == spec.next.getValue(type) - 1) {
                "$type ids have moved: the tree's highest is $highest, the spec allocated " +
                    "${spec.ceilings.getValue(type) + 1} to ${spec.next.getValue(type) - 1}."
            }
        }
        val first = spec.sets.first()
        check(first.obj.getValue("unfired") == spec.ceilings.getValue("obj") + 1) {
            "the spec's first obj ${first.obj["unfired"]} is not one past the ceiling."
        }
    }

    private fun directory(type: String): String = if (type == "struct") "config/struct" else if (type == "model") "models" else "config_$type"

    private fun suffix(type: String): String = if (type == "model") ".glb" else ".json"

    private fun highest(directory: String, suffix: String): Int =
        Files.list(sourceTree.resolve(directory)).use { stream ->
            stream.toList()
                .map { it.fileName.toString() }
                .filter { it.endsWith(suffix) }
                .mapNotNull { it.removeSuffix(suffix).toIntOrNull() }
                .max()
        }

    private fun models(set: UrnSet) {
        val worn = wornModels(set)
        for ((rs3Id, id) in set.models) {
            val name = set.modelNames[rs3Id]
            if (name != null && id != rs3Id) {
                modelNames[id] = name
            }
            record("model", rs3Id, id)
            if (id == rs3Id) {
                continue
            }
            newModels[rs3Id] = id
            val converted = ModelConverter.convert(Rs3Model.read(rs3Tree, rs3Id), id, rs3Id in worn, crosswalk)
            Files.write(SourceModels.path(sourceTree, id), ModelGltf.write(converted))
        }
    }

    private fun wornModels(set: UrnSet): Set<Int> {
        val worn = HashSet<Int>()
        for (role in listOf("norune", "full")) {
            val obj = set.rs3Obj[role] ?: continue
            val definition = rs3Obj(obj)
            val model = definition["primaryMaleModel"]?.jsonPrimitive?.int ?: -1
            if (model > 0) {
                worn.add(model)
            }
        }
        return worn
    }

    private fun rs3Obj(id: Int): JsonObject =
        Json.parseToJsonElement(Files.readString(rs3Tree.resolve("config_obj").resolve("$id.json"))).jsonObject

    private fun objs(set: UrnSet) {
        for (role in roles) {
            val id = set.obj[role] ?: continue
            objNames[id] = set.gameval.getValue(role)
            record("obj", set.rs3Obj.getValue(role), id)
            val template = SourceJsonText.read(sourceTree.resolve("config_obj").resolve("${templates.getValue(role)}.json"))
            val values = LinkedHashMap<String, JsonElement>()
            values["id"] = JsonPrimitive(id)
            values["name"] = JsonPrimitive(set.name.getValue(role))
            values["cost"] = JsonPrimitive(set.cost.getValue(role))
            values["members"] = JsonPrimitive(set.members)
            val parent = certParent[role]
            if (parent != null) {
                values["noteId"] = JsonPrimitive(set.obj.getValue(parent))
            } else {
                values["noteId"] = JsonPrimitive(certOf(set, role))
                values["modelId"] = JsonPrimitive(inventoryModel(set, role))
                val worn = wornModel(set, role)
                values["primaryMaleModel"] = JsonPrimitive(worn)
                values["primaryFemaleModel"] = JsonPrimitive(worn)
                for ((field, value) in set.obj2d) {
                    values[field] = JsonPrimitive(value)
                }
                if (role == "norune") {
                    values["options"] = options(template, set.noruneOption)
                }
            }
            val category = set.geCategory[role]
            val params = if (category == null) null else JsonObject(mapOf("2195" to JsonPrimitive(category)))
            SourceJsonText.write(sourceTree.resolve("config_obj").resolve("$id.json"), merge(template, values, params))
        }
    }

    private fun options(template: JsonObject, first: String): JsonArray {
        val existing = template["options"]!!.jsonArray
        return JsonArray(existing.mapIndexed { index, value -> if (index == 0) JsonPrimitive(first) else value })
    }

    private fun certOf(set: UrnSet, role: String): Int {
        val cert = certParent.entries.firstOrNull { it.value == role }?.key ?: return -1
        return set.obj[cert] ?: -1
    }

    private fun inventoryModel(set: UrnSet, role: String): Int {
        val rs3 = rs3Obj(set.rs3Obj.getValue(role))
        val model = rs3["modelIds"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.int ?: return 0
        return set.models.getValue(model)
    }

    private fun wornModel(set: UrnSet, role: String): Int {
        val rs3 = rs3Obj(set.rs3Obj.getValue(role))
        val model = rs3["primaryMaleModel"]?.jsonPrimitive?.int ?: -1
        return if (model > 0) set.models.getValue(model) else -1
    }

    private fun merge(template: JsonObject, values: Map<String, JsonElement>, params: JsonObject?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in template) {
            when (key) {
                "opcodeOrder" -> continue
                "params" -> if (params != null) out["params"] = params
                "stringId" -> {
                    if (params != null && !out.containsKey("params")) {
                        out["params"] = params
                    }
                    out[key] = value
                }
                else -> out[key] = values[key] ?: value
            }
        }
        return JsonObject(out)
    }

    private fun seqs(set: UrnSet) {
        for ((role, id) in set.seq) {
            seqNames[id] = set.seqName.getValue(role)
            record("seq", set.rs3Seq.getValue(role), id)
            val template = SourceJsonText.read(sourceTree.resolve("config_seq").resolve("${set.cloneSeq.getValue(role)}.json"))
            val held = if (role == "teleport") set.obj.getValue("full") else set.obj.getValue("norune")
            val out = LinkedHashMap<String, JsonElement>()
            for ((key, value) in template) {
                when (key) {
                    "opcodeOrder" -> continue
                    "id" -> out[key] = JsonPrimitive(id)
                    "rightHandItem" -> out[key] = JsonPrimitive(held)
                    else -> out[key] = value
                }
            }
            SourceJsonText.write(sourceTree.resolve("config_seq").resolve("$id.json"), JsonObject(out))
        }
    }

    private fun struct(set: UrnSet) {
        structNames[set.struct] = set.structName
        record("struct", set.rs3Struct, set.struct)
        enumRows[set.enumRow] = set.struct
        val params = LinkedHashMap<String, JsonElement>()
        params["2213"] = JsonPrimitive(set.obj.getValue("rune"))
        params["2210"] = JsonPrimitive(set.structLabel)
        params["2212"] = JsonPrimitive(set.level)
        params["2215"] = JsonPrimitive(SKILL_GUIDE_CRAFTING)
        params["2220"] = JsonPrimitive(SKILL_GUIDE_FILTER_OBJECTS)
        if (set.members) {
            params["2217"] = JsonPrimitive(1)
        }
        val definition = JsonObject(
            mapOf(
                "id" to JsonPrimitive(set.struct),
                "params" to JsonObject(params)
            )
        )
        SourceJsonText.write(sourceTree.resolve("config").resolve("struct").resolve("${set.struct}.json"), definition)
    }

    private fun record(type: String, rs3: Int, id: Int) {
        ids.getOrPut(type) { LinkedHashMap() }[rs3] = id
    }

    private fun writeIdMap() {
        val root = JsonObject(
            ids.toSortedMap().mapValues { entry ->
                JsonObject(entry.value.toSortedMap().mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) })
            }
        )
        val path = Path.of("docs", "reference", "rs3-urns-ids.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true; prettyPrintIndent = "  " }
            .encodeToString(JsonObject.serializer(), root) + "\n")
    }

    companion object {

        private const val SKILL_GUIDE_ENUM = 5465

        private const val SKILL_GUIDE_CRAFTING = 11

        private const val SKILL_GUIDE_FILTER_OBJECTS = 4
    }
}
