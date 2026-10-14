package org.darkan.tools.rs3port

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import java.nio.file.Files
import java.nio.file.Path

class BillboardTable(private val entries: List<IntArray>, private val signatures: Map<Int, String>) {

    fun lowest(material: Int, size2d: Int, size3d: Int): Int? =
        entries.filter { it[1] == material && it[2] == size2d && it[3] == size3d }.minOfOrNull { it[0] }

    fun equivalent(a: Int, b: Int): Boolean = a == b || (signatures[a] != null && signatures[a] == signatures[b])

    companion object {

        private val IDENTITY = setOf("id", "opcodeOrder")

        fun read(tree: Path): BillboardTable {
            val directory = tree.resolve("config_billboard")
            val signatures = HashMap<Int, String>()
            val entries = Files.list(directory).use { stream ->
                stream.toList().mapNotNull { path ->
                    val id = path.fileName.toString().removeSuffix(".json").toIntOrNull() ?: return@mapNotNull null
                    val json = SourceJsonText.read(path)
                    signatures[id] = json.filterKeys { it !in IDENTITY }.toString()
                    intArrayOf(
                        id,
                        json["materialId"]!!.jsonPrimitive.int,
                        json["size2d"]!!.jsonPrimitive.int,
                        json["size3d"]!!.jsonPrimitive.int
                    )
                }
            }
            return BillboardTable(entries, signatures)
        }
    }
}

class SpotanimImporter(
    private val rs3Tree: Path,
    private val sourceTree: Path,
    private val spec: SpotanimSpec,
    private val crosswalk: Crosswalk
) {

    private val directories = mapOf(
        "graphic" to "graphics",
        "texture" to "textures",
        "material" to "materials",
        "base" to "bases",
        "anim" to "anims",
        "seq" to "config_seq",
        "producer" to "config_particle/producer",
        "effector" to "config_particle/particle",
        "model" to "models",
        "spotanim" to "config_spotanim"
    )

    private val writer = TreeWriter(sourceTree)

    private val findings = GrammarFindings()

    private val graphicNames = LinkedHashMap<Int, String>()

    private val materialNames = LinkedHashMap<Int, String>()

    private val seqNames = LinkedHashMap<Int, String>()

    private val modelNames = LinkedHashMap<Int, String>()

    private val spotanimNames = LinkedHashMap<Int, String>()

    private val bases = HashMap<Int, AnimationBaseDefinition>()

    private val frameFiles = HashMap<Int, Set<Int>>()

    private val newMaterials = spec.materials.associate { it.rs3 to it.material }

    private val producers = spec.producers.associate { it.rs3 to it.id }

    private val effectors = spec.effectors.associate { it.rs3 to it.id }

    private val allocated = spec.allocations().mapValues { it.value.toSet() }

    private val context = ClientContext(
        exists = ::exists,
        frameExists = { archive, file -> frameFiles[archive]?.contains(file) ?: false },
        baseTypes = { id -> bases.values.firstOrNull { it.id == id }?.types ?: shippedBaseTypes(id) }
    )

    fun run() {
        checkCeilings()
        materials()
        bases()
        frames()
        seqs()
        particles()
        models()
        spotanims()
        findings.refuse()
        val written = writer.flush()
        indices()
        catalogs()
        for ((path, state) in written) {
            println("  $state $path")
        }
        println("files ${written.size}: ${written.groupingBy { it.second }.eachCount()}")
    }

    private fun checkCeilings() {
        for ((type, ids) in spec.allocations()) {
            if (ids.isEmpty()) {
                continue
            }
            val ceiling = spec.ceilings[type] ?: throw IllegalArgumentException("The spec names no $type ceiling.")
            val expected = (ceiling + 1..ceiling + ids.size).toList()
            check(ids == expected) { "The spec allocates $type $ids; next-free from its ceiling $ceiling is $expected." }
            val highest = highest(directories.getValue(type))
            check(highest == ceiling || highest == ceiling + ids.size) {
                "$type ids have moved: the tree's highest is $highest, the spec allocated ${expected.first()} to ${expected.last()}."
            }
        }
    }

    private fun highest(directory: String): Int = Files.list(sourceTree.resolve(directory)).use { stream ->
        stream.toList().mapNotNull { it.fileName.toString().substringBefore('.').toIntOrNull() }.max()
    }

    private fun exists(kind: String, id: Int): Boolean {
        if (allocated[kind]?.contains(id) == true) {
            return true
        }
        val path = when (kind) {
            "model" -> "models/$id.glb"
            "seq" -> "config_seq/$id.json"
            "material" -> "materials/$id.json"
            "graphic" -> "graphics/$id/0.png"
            "producer" -> "config_particle/producer/$id.json"
            "effector" -> "config_particle/particle/$id.json"
            "billboard" -> "config_billboard/$id.json"
            "sound" -> "synth_sounds/$id.json"
            else -> throw IllegalArgumentException("No tree path for $kind.")
        }
        return Files.exists(sourceTree.resolve(path))
    }

    private fun shippedBaseTypes(id: Int): IntArray? {
        val path = sourceTree.resolve("bases").resolve("$id.json")
        if (!Files.exists(path)) {
            return null
        }
        val types = SourceJsonText.read(path)["types"]!!.jsonArray
        return IntArray(types.size) { types[it].jsonPrimitive.int }
    }

    private fun material(rs3: Int): Int = newMaterials[rs3] ?: spec.reusedMaterials[rs3] ?: crosswalk.materials[rs3]
        ?: throw IllegalArgumentException("RS3 material $rs3 has no decision in the spec or the crosswalk.")

    private fun materials() {
        val template = SourceJsonText.read(sourceTree.resolve("textures").resolve("${Rs3Materials.SPRITE_TEXTURE_TEMPLATE}.json"))
        val definitions = ArrayList<MaterialDefinition>()
        for (entry in spec.materials) {
            val json = Rs3Materials.json(rs3Tree, entry.rs3)
            val texture = json["unknown2"]!!.jsonPrimitive.int
            check(texture == entry.rs3Texture) { "RS3 material ${entry.rs3} draws texture $texture, not ${entry.rs3Texture}." }
            val sprites = Rs3Materials.sprites(Rs3Materials.image(rs3Tree, entry.rs3Texture))
            check((sprites.opacity != null) == (entry.opacityGraphic != null)) {
                if (sprites.opacity != null) {
                    "RS3 texture ${entry.rs3Texture} is partly transparent; the spec must allocate it an opacityGraphic."
                } else {
                    "RS3 texture ${entry.rs3Texture} is opaque; the spec's opacityGraphic ${entry.opacityGraphic} would be unused."
                }
            }
            graphic(entry.graphic, SourceCodecFiles.graphic(sourceTree, entry.graphic, sprites.colour))
            graphicNames[entry.graphic] = entry.gameval
            val opacity = entry.opacityGraphic
            if (opacity != null) {
                graphic(opacity, SourceCodecFiles.graphic(sourceTree, opacity, sprites.opacity!!))
                graphicNames[opacity] = "${entry.gameval}$ALPHA_SUFFIX"
            }
            val definition = Rs3Materials.texture(template, entry.texture, entry.graphic, opacity)
            val converted = SourceCodecFiles.texture(sourceTree, definition)
            accept("texture ${entry.texture}", converted) { ClientGrammar.texture(definition, it, context) }
            definitions.add(Rs3Materials.convert(json, entry.material))
            materialNames[entry.material] = entry.gameval
        }
        if (definitions.isNotEmpty()) {
            accept("materials", SourceCodecFiles.materials(sourceTree, definitions)) { ClientGrammar.materials(definitions, it) }
        }
    }

    private fun graphic(id: Int, converted: Converted) {
        accept("graphic $id", converted) { ClientGrammar.sprite(id, it) }
    }

    private fun bases() {
        for (entry in spec.bases) {
            val base = Rs3Anims.convert(Rs3Anims.base(rs3Tree, entry.rs3), entry.id)
            bases[entry.rs3] = base
            accept("base ${entry.id}", SourceCodecFiles.base(sourceTree, base)) { ClientGrammar.base(base, it) }
        }
    }

    private fun frames() {
        for (entry in spec.spotanims) {
            val frames = Rs3Anims.frames(rs3Tree, entry.rs3Anim).sortedBy { it.file }
            val rs3Base = frames.map { it.base }.distinct().single()
            val base = bases[rs3Base]
                ?: throw IllegalArgumentException("RS3 anim ${entry.rs3Anim} moves base $rs3Base, which the spec does not port.")
            val converted = frames.map { Rs3Anims.convert(it, base.types, entry.anim, base.id) }
            frameFiles[entry.anim] = converted.map { it.id and ((1 shl AnimationFrameDecoder.BITS) - 1) }.toSet()
            val files = SourceCodecFiles.frames(sourceTree, entry.anim, converted, base)
            writer.add(files)
            for ((index, frame) in converted.withIndex()) {
                findings.check("frame ${entry.anim}/${frame.id and 0xffff}", ClientGrammar.frame(frame, files.packed[index], context))
            }
        }
    }

    private fun seqs() {
        val archives = spec.spotanims.associate { it.rs3Anim to it.anim }
        for (entry in spec.spotanims) {
            val seq = Rs3Configs.seq(Rs3Configs.seqJson(rs3Tree, entry.rs3Seq), entry.seq.id) { archive ->
                archives[archive] ?: throw IllegalArgumentException("RS3 seq ${entry.rs3Seq} plays anim $archive, which the spec does not port.")
            }
            accept("seq ${entry.seq.id}", SourceCodecFiles.seq(sourceTree, seq)) { ClientGrammar.seq(seq, it, context) }
            seqNames[entry.seq.id] = entry.seq.gameval
        }
    }

    private fun particles() {
        val ids = ParticleIds(::material, ::producer, ::effector)
        for (entry in spec.producers) {
            val producer = Rs3Particles.producer(Rs3Particles.producerJson(rs3Tree, entry.rs3), entry.id, ids)
            accept("producer ${entry.id}", SourceCodecFiles.producer(sourceTree, producer)) { ClientGrammar.producer(producer, it, context) }
        }
        for (entry in spec.effectors) {
            val effector = Rs3Particles.effector(Rs3Particles.effectorJson(rs3Tree, entry.rs3), entry.id)
            accept("effector ${entry.id}", SourceCodecFiles.effector(sourceTree, effector)) { ClientGrammar.effector(effector, it) }
        }
    }

    private fun producer(rs3: Int): Int =
        producers[rs3] ?: throw IllegalArgumentException("RS3 producer $rs3 is not in the spec.")

    private fun effector(rs3: Int): Int =
        effectors[rs3] ?: throw IllegalArgumentException("RS3 effector $rs3 is not in the spec.")

    private fun models() {
        val billboards = BillboardTable.read(sourceTree)
        val tables = ModelTables(
            material = ::material,
            template = crosswalk::projectionOrNull,
            producer = ::producer,
            effector = ::effector,
            billboard = { rs3Material, size2d, size3d ->
                val material = material(rs3Material)
                val id = billboards.lowest(material, size2d, size3d)
                    ?: throw IllegalArgumentException("No 727 billboard draws material $material at $size2d x $size3d.")
                check(id in spec.reusedBillboards) { "Billboard $id is not a decision the spec records in reusedBillboards." }
                id
            },
            shading = true
        )
        for (entry in spec.spotanims) {
            val model = ModelConverter.convert(Rs3Model.read(rs3Tree, entry.rs3Model), entry.model.id, false, tables)
            accept("model ${entry.model.id}", SourceCodecFiles.model(sourceTree, model)) { ClientGrammar.model(model, it, context) }
            modelNames[entry.model.id] = entry.model.gameval
        }
    }

    private fun spotanims() {
        for (entry in spec.spotanims) {
            val json = Rs3Configs.spotanimJson(rs3Tree, entry.rs3Spotanim)
            check(json["modelId"]!!.jsonPrimitive.int == entry.rs3Model && json["animationId"]!!.jsonPrimitive.int == entry.rs3Seq) {
                "RS3 spotanim ${entry.rs3Spotanim} is not model ${entry.rs3Model} playing seq ${entry.rs3Seq}."
            }
            val spotanim = Rs3Configs.spotanim(json, entry.spotanim.id, entry.model.id, entry.seq.id)
            accept("spotanim ${entry.spotanim.id}", SourceCodecFiles.spotanim(sourceTree, spotanim)) { ClientGrammar.spotanim(spotanim, it, context) }
            spotanimNames[entry.spotanim.id] = entry.spotanim.gameval
        }
    }

    private fun accept(record: String, converted: Converted, grammar: (ByteArray) -> List<String>) {
        writer.add(converted)
        findings.check(record, grammar(converted.packed.single()))
    }

    private fun indices() {
        val added = listOf(
            "models" to SourceEdits.archiveIndex(sourceTree, "models", modelNames.keys.associateWith { SourceEdits.GZIP }),
            "anims" to SourceEdits.archiveIndex(sourceTree, "anims", spec.spotanims.associate { it.anim to SourceEdits.VERSIONED_GZIP }),
            "bases" to SourceEdits.archiveIndex(sourceTree, "bases", spec.bases.associate { it.id to SourceEdits.VERSIONED_GZIP }),
            "textures" to SourceEdits.archiveIndex(sourceTree, "textures", spec.materials.associate { it.texture to SourceEdits.GZIP }),
            "graphics" to SourceEdits.archiveIndex(sourceTree, "graphics", graphicNames.mapValues { SourceEdits.named(it.value.hashCode()) })
        )
        println("index lines added: " + added.joinToString { "${it.first} ${it.second}" })
    }

    private fun catalogs() {
        val added = listOf(
            "graphic" to SourceEdits.catalog(sourceTree, "graphic", graphicNames),
            "material" to SourceEdits.catalog(sourceTree, "material", materialNames),
            "seq" to SourceEdits.catalog(sourceTree, "seq", seqNames),
            "model" to SourceEdits.catalog(sourceTree, "model", modelNames),
            "spotanim" to SourceEdits.catalog(sourceTree, "spotanim", spotanimNames, DERIVED)
        )
        SourceEdits.declarations(sourceTree, "Graphic", "graphic", graphicNames)
        SourceEdits.declarations(sourceTree, "Seq", "seq", seqNames)
        SourceEdits.declarations(sourceTree, "Model", "model", modelNames)
        SourceEdits.declarations(sourceTree, "Spotanim", "spotanim", spotanimNames)
        println("catalog entries added: " + added.joinToString { "${it.first} ${it.second}" })
    }

    private companion object {

        private const val ALPHA_SUFFIX = "_alpha"

        private const val DERIVED = "derived"
    }
}
