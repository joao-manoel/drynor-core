package org.drynor.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.nio.file.Files
import java.nio.file.Path

class UrnModelUse(val id: Int, val worn: Boolean)

object UrnCorpus {

    fun read(sourceTree: Path, obj: Int): JsonObject? {
        val file = sourceTree.resolve("config_obj").resolve("$obj.json")
        return if (Files.exists(file)) SourceJsonText.read(file) else null
    }

    fun uses(sourceTree: Path, objs: Collection<Int>): List<UrnModelUse> {
        val worn = LinkedHashMap<Int, Boolean>()
        for (obj in objs) {
            val definition = read(sourceTree, obj) ?: continue
            val model = definition["modelId"]?.jsonPrimitive?.int ?: -1
            if (model > 0) {
                worn.putIfAbsent(model, false)
            }
            for (field in listOf("primaryMaleModel", "primaryFemaleModel")) {
                val id = definition[field]?.jsonPrimitive?.int ?: -1
                if (id > 0) {
                    worn[id] = true
                }
            }
        }
        return worn.entries.sortedBy { it.key }.map { UrnModelUse(it.key, it.value) }
    }

    fun shared(spec: UrnSpec, sourceTree: Path, rs3Tree: Path): List<CorpusModel> {
        val objs = spec.existing.flatMap { it.obj.values }
        return uses(sourceTree, objs)
            .filter { Rs3Model.exists(rs3Tree, it.id) }
            .map { CorpusModel(it.id, it.id, it.worn) }
    }
}

private const val CROSSWALK = "docs/reference/rs3-material-crosswalk.json"

private const val DEFAULT_RS3_TREE = "../drynor-3-undercut/unpacked-cache"

private const val DEFAULT_SOURCE_TREE = "cache-source"

private const val OVERRIDES = "docs/reference/rs3-material-overrides.json"

fun runRs3Port(args: List<String>) {
    when (args.firstOrNull()?.lowercase()) {
        "materials" -> materials(args.drop(1))
        "model" -> model(args.drop(1))
        "calibrate" -> calibrate(args.drop(1))
        "calibrate-frames" -> FrameCalibration.run(Path.of(args[1]), Path.of(args[2]))
        "calibrate-particles" -> ParticleCalibration.run(Path.of(args[1]), Path.of(args[2]))
        "calibrate-materials" -> MaterialCalibration.run(Path.of(args[1]), Path.of(args[2]), crosswalkOrNull())
        "calibrate-attachments" -> attachments(args.drop(1))
        "urns" -> urns(args.drop(1))
        "spotanims" -> spotanims(args.drop(1))
        "opacity" -> AlphaSpriteImporter(Path.of(args[1]), AlphaSpriteSpec.read(Path.of(args[2]))).run()
        "sprite" -> sprite(args.drop(1))
        else -> usage()
    }
}

private fun usage() {
    println(
        """
        rs3port materials <rs3-tree> <727-tree> [spec.json]
        rs3port model <rs3-tree> <727-tree> <rs3Id> --id <727Id> --role <role> [--out <dir>]
        rs3port calibrate <rs3-tree> <727-tree> [spec.json] [--out <dir>]
        rs3port calibrate-frames <rs3-tree> <727-tree>
        rs3port calibrate-particles <rs3-tree> <727-tree>
        rs3port calibrate-materials <rs3-tree> <727-tree>
        rs3port calibrate-attachments <rs3-tree> <727-tree> [--ids <file>] [--limit <n>] [--urns]
        rs3port urns <rs3-tree> <727-tree> [spec.json]
        rs3port spotanims <rs3-tree> <727-tree> <spec.json>
        rs3port opacity <727-tree> <spec.json>
        rs3port sprite <rs3Graphic> <727Graphic> <gameval> [--size <px>] [--background none|roundel|keyed] [--rs3 <rs3-tree>] [--tree <727-tree>] [--rename]
        rs3port sprite --obj <rs3Obj> <727Graphic> <gameval> [--size <px>] [--overrides <materials.json>] [--rs3 <rs3-tree>] [--tree <727-tree>] [--rename]
        """.trimIndent()
    )
}

private fun flag(args: List<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

private val SPRITE_VALUED_FLAGS = setOf("--obj", "--size", "--background", "--rs3", "--tree", "--overrides")

private fun positionals(args: List<String>, valued: Set<String>): List<String> {
    val out = ArrayList<String>()
    var index = 0
    while (index < args.size) {
        when {
            args[index] in valued -> index += 2
            args[index].startsWith("--") -> index++
            else -> out.add(args[index++])
        }
    }
    return out
}

private fun sprite(args: List<String>) {
    val obj = flag(args, "--obj")?.toInt()
    val positional = positionals(args, SPRITE_VALUED_FLAGS)
    val expected = if (obj != null) 2 else 3
    if (positional.size != expected) {
        usage()
        return
    }
    val rest = if (obj != null) positional else positional.drop(1)
    val import = SpriteImport(
        source = if (obj != null) SpriteSource.ObjIcon(obj) else SpriteSource.Graphic(positional[0].toInt()),
        graphic = rest[0].toInt(),
        gameval = rest[1],
        size = flag(args, "--size")?.toInt() ?: SpriteImport.DEFAULT_SIZE,
        background = flag(args, "--background")?.let { SpriteBackground.valueOf(it.uppercase()) } ?: SpriteBackground.NONE,
        rename = args.contains("--rename")
    )
    val rs3Tree = Path.of(flag(args, "--rs3") ?: DEFAULT_RS3_TREE)
    val sourceTree = Path.of(flag(args, "--tree") ?: DEFAULT_SOURCE_TREE)
    val overrides = flag(args, "--overrides")?.let { Crosswalk.overrides(Path.of(it)) }.orEmpty()
    SpriteImporter(rs3Tree, sourceTree) { crosswalk().with(overrides) }.run(import)
}

private fun crosswalk(): Crosswalk = crosswalkOrNull() ?: throw IllegalStateException("$CROSSWALK is missing; run `rs3port materials` first.")

private fun crosswalkOrNull(): Crosswalk? {
    val path = Path.of(CROSSWALK)
    return if (Files.exists(path)) Crosswalk.read(path) else null
}

private fun materials(args: List<String>) {
    val rs3Tree = Path.of(args[0])
    val sourceTree = Path.of(args[1])
    val spec = UrnSpec.read(Path.of(args.getOrNull(2) ?: UrnSpec.DEFAULT.toString()))
    val corpus = UrnCorpus.shared(spec, sourceTree, rs3Tree)
    val learner = CrosswalkLearner(rs3Tree, sourceTree)
    val learned = learner.learn(corpus, Crosswalk.overrides(Path.of(OVERRIDES)))
    learned.write(Path.of(CROSSWALK), learner.models, learner.matched)
    println("corpus ${learner.models} models, ${learner.matched} matched faces")
    for (rs3 in learned.materials.keys.sorted()) {
        val counts = learned.counts[rs3].orEmpty().entries.sortedByDescending { it.value }
        val source = if (learned.overrides.containsKey(rs3)) "override" else "learned"
        println("  rs3 $rs3 -> ${learned.materials[rs3]} ($source) ${counts.joinToString { "${it.key}x${it.value}" }}")
    }
    for (key in learned.projections.keys.sorted()) {
        println("  projection $key -> ${learned.projections[key]!!.key()}")
    }
    println("wrote $CROSSWALK")
}

private fun model(args: List<String>) {
    val rs3Tree = Path.of(args[0])
    val sourceTree = Path.of(args[1])
    val rs3Id = args[2].toInt()
    val id = flag(args, "--id")?.toInt() ?: rs3Id
    val worn = flag(args, "--role")?.startsWith("worn") ?: false
    val out = flag(args, "--out")
    val converted = ModelConverter.convert(Rs3Model.read(rs3Tree, rs3Id), id, worn, crosswalk())
    val directory = if (out != null) Path.of(out) else sourceTree.resolve("models")
    Files.createDirectories(directory)
    Files.write(directory.resolve("$id.glb"), ModelGltf.write(converted))
    if (out == null) {
        SourceEdits.modelIndex(sourceTree, listOf(id))
    }
    println("rs3 $rs3Id -> model $id: ${converted.vertexCount} vertices, ${converted.faceCount} faces")
}

private fun attachments(args: List<String>) {
    val rs3Tree = Path.of(args[0])
    val sourceTree = Path.of(args[1])
    val listed = flag(args, "--ids")?.let { file -> Files.readAllLines(Path.of(file)).mapNotNull { it.trim().toIntOrNull() } }
    val ids = when {
        listed != null -> listed
        args.contains("--urns") -> UrnCorpus.shared(UrnSpec.read(UrnSpec.DEFAULT), sourceTree, rs3Tree).map { it.sourceId }
        else -> AttachmentCalibration.corpus(rs3Tree, sourceTree)
    }
    val limit = flag(args, "--limit")?.toInt() ?: ids.size
    AttachmentCalibration.run(rs3Tree, sourceTree, ids.take(limit))
}

private class Row(
    val id: Int,
    val worn: Boolean,
    val vertices: Boolean,
    val corners: Boolean,
    val faces: Int,
    val matched: Int,
    val colours: Int,
    val priorities: Int,
    val materials: Int,
    val skins: Int,
    val types: Int,
    val winding: Int,
    val projections: Int,
    val textureExact: Boolean
)

private fun calibrate(args: List<String>) {
    val rs3Tree = Path.of(args[0])
    val sourceTree = Path.of(args[1])
    val spec = UrnSpec.read(Path.of(args.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: UrnSpec.DEFAULT.toString()))
    val table = crosswalk()
    val out = flag(args, "--out")?.let { Path.of(it) }
    out?.let { Files.createDirectories(it) }
    val rows = ArrayList<Row>()
    for (entry in UrnCorpus.shared(spec, sourceTree, rs3Tree)) {
        val rs3 = Rs3Model.read(rs3Tree, entry.rs3Id)
        val shipped = SourceModels.read(sourceTree, entry.sourceId) ?: continue
        val converted = ModelConverter.convert(rs3, entry.sourceId, entry.worn, table)
        out?.let { Files.write(it.resolve("${entry.sourceId}.glb"), ModelGltf.write(converted)) }
        rows.add(compare(rs3, shipped, converted, entry.worn))
    }
    report(rows)
}

private fun compare(rs3: Rs3Model, shipped: ModelDefinition, converted: ModelDefinition, worn: Boolean): Row {
    val match = FaceMatcher.match(rs3, shipped)
    var colours = 0
    var priorities = 0
    var materials = 0
    var skins = 0
    var types = 0
    var winding = 0
    var projections = 0
    val faces = match.all()
    for (face in faces) {
        if (converted.faceColors[face.source] == shipped.faceColors[face.target]) {
            colours++
        }
        if (priority(converted, face.source) == priority(shipped, face.target)) {
            priorities++
        }
        if (material(converted, face.source) == material(shipped, face.target)) {
            materials++
        }
        if ((converted.faceSkins?.get(face.source) ?: 0) == (shipped.faceSkins?.get(face.target) ?: 0)) {
            skins++
        }
        if (mappingOf(converted, face.source) == mappingOf(shipped, face.target)) {
            types++
        }
        if (wound(converted, face.source) == wound(shipped, face.target)) {
            winding++
        }
        if (projectionOf(converted, face.source) == projectionOf(shipped, face.target)) {
            projections++
        }
    }
    return Row(
        id = shipped.id,
        worn = worn,
        vertices = positions(converted, true) == positions(shipped, true),
        corners = positions(converted, false) == positions(shipped, false),
        faces = shipped.faceCount,
        matched = faces.size,
        colours = colours,
        priorities = priorities,
        materials = materials,
        skins = skins,
        types = types,
        winding = winding,
        projections = projections,
        textureExact = textureBlock(converted) == textureBlock(shipped)
    )
}

private fun wound(model: ModelDefinition, face: Int): List<List<Int>> {
    val corners = listOf(model.faceA[face], model.faceB[face], model.faceC[face]).map {
        listOf(model.vertexX[it], model.vertexY[it], model.vertexZ[it])
    }
    val order = compareBy<List<Int>>({ it[0] }, { it[1] }, { it[2] })
    var first = 0
    for (corner in 1 until 3) {
        if (order.compare(corners[corner], corners[first]) < 0) {
            first = corner
        }
    }
    return listOf(corners[first], corners[(first + 1) % 3], corners[(first + 2) % 3])
}

private fun positions(model: ModelDefinition, drawn: Boolean): List<List<Int>> {
    val used = HashSet<Int>()
    for (face in 0 until model.faceCount) {
        used.add(model.faceA[face])
        used.add(model.faceB[face])
        used.add(model.faceC[face])
    }
    return (0 until model.vertexCount)
        .filter { used.contains(it) == drawn }
        .map { listOf(model.vertexX[it], model.vertexY[it], model.vertexZ[it]) }
        .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
}

private fun projectionOf(model: ModelDefinition, face: Int): String {
    val pointer = model.faceTexturePointers?.get(face)?.toInt() ?: -1
    if (pointer < 0) {
        return "none"
    }
    val mapping = model.texturesMapping?.get(pointer)?.toInt() ?: 0
    if (mapping == 0) {
        val out = StringBuilder("planar ")
        for (corner in listOf(model.textureFaceX!![pointer], model.textureFaceY!![pointer], model.textureFaceZ!![pointer])) {
            val vertex = corner.toInt()
            out.append(model.vertexX[vertex]).append('/').append(model.vertexY[vertex]).append('/')
                .append(model.vertexZ[vertex]).append(' ')
        }
        return out.toString()
    }
    val complex = (0 until pointer).count { (model.texturesMapping?.get(it)?.toInt() ?: 0) != 0 }
    return listOf(
        mapping,
        model.textureFaceX!![pointer].toInt() and 0xffff,
        model.textureFaceY!![pointer].toInt() and 0xffff,
        model.textureFaceZ!![pointer].toInt() and 0xffff,
        model.texturesScaleX?.get(complex),
        model.texturesScaleY?.get(complex),
        model.texturesScaleZ?.get(complex),
        model.texturesRotation?.get(complex)?.toInt(),
        model.texturesDirection?.get(complex)?.toInt(),
        model.texturesSpeed?.get(complex)
    ).joinToString("/")
}

private fun priority(model: ModelDefinition, face: Int): Int =
    model.facePriorities?.get(face)?.toInt() ?: model.modelPriority

private fun material(model: ModelDefinition, face: Int): Int = model.faceTextures?.get(face)?.toInt() ?: -1

private fun mappingOf(model: ModelDefinition, face: Int): Int {
    val pointer = model.faceTexturePointers?.get(face)?.toInt() ?: -1
    if (pointer < 0) {
        return -1
    }
    return model.texturesMapping?.get(pointer)?.toInt() ?: -1
}

private fun textureBlock(model: ModelDefinition): String {
    val out = StringBuilder()
    for (pointer in 0 until model.textureTriangleCount) {
        val mapping = model.texturesMapping?.get(pointer)?.toInt() ?: 0
        out.append(mapping).append(':')
        if (mapping == 0) {
            for (corner in listOf(model.textureFaceX!![pointer], model.textureFaceY!![pointer], model.textureFaceZ!![pointer])) {
                val vertex = corner.toInt()
                out.append(model.vertexX[vertex]).append('/')
                    .append(model.vertexY[vertex]).append('/')
                    .append(model.vertexZ[vertex]).append(' ')
            }
        } else {
            out.append(model.textureFaceX!![pointer].toInt() and 0xffff).append('/')
                .append(model.textureFaceY!![pointer].toInt() and 0xffff).append('/')
                .append(model.textureFaceZ!![pointer].toInt() and 0xffff).append(' ')
        }
        out.append('|')
    }
    val complex = (0 until model.textureTriangleCount).count { (model.texturesMapping?.get(it)?.toInt() ?: 0) != 0 }
    for (index in 0 until complex) {
        out.append(model.texturesScaleX?.get(index)).append('/')
            .append(model.texturesScaleY?.get(index)).append('/')
            .append(model.texturesScaleZ?.get(index)).append('/')
            .append(model.texturesRotation?.get(index)?.toInt()).append('/')
            .append(model.texturesDirection?.get(index)?.toInt()).append('/')
            .append(model.texturesSpeed?.get(index)).append('|')
    }
    return out.toString()
}

private fun report(rows: List<Row>) {
    println("model    worn verts corners faces matched colours priorities materials skins winding type    projection texture")
    for (row in rows) {
        println(
            "%-8d %-4s %-5s %-7s %-5d %-7d %-7s %-10s %-9s %-5s %-7s %-7s %-10s %s".format(
                row.id,
                if (row.worn) "yes" else "no",
                if (row.vertices) "ok" else "DIFF",
                if (row.corners) "ok" else "+-1",
                row.faces,
                row.matched,
                percent(row.colours, row.matched),
                percent(row.priorities, row.matched),
                percent(row.materials, row.matched),
                percent(row.skins, row.matched),
                percent(row.winding, row.matched),
                percent(row.types, row.matched),
                percent(row.projections, row.matched),
                if (row.textureExact) "exact" else "template"
            )
        )
    }
    val faces = rows.sumOf { it.matched }
    println()
    println("models ${rows.size}, faces matched $faces of ${rows.sumOf { it.faces }}")
    println("  vertices     ${rows.count { it.vertices }}/${rows.size} models exact, texture corners ${rows.count { it.corners }}/${rows.size}")
    println("  colours      ${percent(rows.sumOf { it.colours }, faces)}")
    println("  priorities   ${percent(rows.sumOf { it.priorities }, faces)}")
    println("  materials    ${percent(rows.sumOf { it.materials }, faces)}")
    println("  skins        ${percent(rows.sumOf { it.skins }, faces)}")
    println("  winding      ${percent(rows.sumOf { it.winding }, faces)}")
    println("  mapping type ${percent(rows.sumOf { it.types }, faces)}")
    println("  projection   ${percent(rows.sumOf { it.projections }, faces)} of faces, ${rows.count { it.textureExact }}/${rows.size} models with the whole block exact")
}

private fun urns(args: List<String>) {
    val rs3Tree = Path.of(args[0])
    val sourceTree = Path.of(args[1])
    val spec = UrnSpec.read(Path.of(args.getOrNull(2) ?: UrnSpec.DEFAULT.toString()))
    UrnImporter(rs3Tree, sourceTree, spec, crosswalk()).run()
}

private fun spotanims(args: List<String>) {
    val rs3Tree = Path.of(args[0])
    val sourceTree = Path.of(args[1])
    SpotanimImporter(rs3Tree, sourceTree, SpotanimSpec.read(Path.of(args[2])), crosswalk()).run()
}
