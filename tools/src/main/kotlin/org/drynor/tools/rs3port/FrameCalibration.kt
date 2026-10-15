package org.drynor.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.source.codec.Gltf
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

object FrameCalibration {

    private const val TURN = 4096

    private const val EXACT = 0

    private const val DIFFER = 1

    private const val PRESENCE = 2

    private class ShippedFrame(val file: Int, val base: Int, val flags: IntArray, val values: IntArray)

    private class PairResult(val source: Int, val rs3: Int, var frames: Int = 0, var exact: Int = 0, var compared: Int = 0) {
        val differences = ArrayList<String>()
    }

    fun run(rs3Tree: Path, sourceTree: Path) {
        val pairs = pairs(rs3Tree, sourceTree)
        val tally = TreeMap<String, IntArray>()
        val sameFlags = IntArray(3)
        var rotationsExactInt = 0
        var rotationsExactTurn = 0
        val results = ArrayList<PairResult>()
        val shippedTypes = HashMap<Int, IntArray>()
        val rs3Types = HashMap<Int, IntArray>()
        for (pair in pairs.keys) {
            val result = PairResult(pair.first, pair.second)
            results.add(result)
            val rs3Frames = Rs3Anims.frames(rs3Tree, pair.second).associateBy { it.file }
            for (frame in shipped(sourceTree, pair.first)) {
                val rs3 = rs3Frames[frame.file] ?: continue
                val types = shippedTypes.getOrPut(frame.base) { baseTypes(sourceTree, frame.base) }
                val theirTypes = rs3Types.getOrPut(rs3.base) { Rs3Anims.base(rs3Tree, rs3.base).types }
                if (!types.contentEquals(theirTypes)) {
                    continue
                }
                result.compared++
                val converted = Rs3Anims.convert(rs3, types, pair.first, frame.base)
                val mine = Rs3Anims.components(converted.flags, converted.values)
                val theirs = Rs3Anims.components(frame.flags, frame.values)
                val flagsSame = padded(converted.flags, frame.flags)
                var frameExact = true
                for (key in (mine.keys + theirs.keys).sorted()) {
                    val transform = key / 3
                    val axis = key % 3
                    val type = types.getOrElse(transform) { -1 }
                    val a = mine[key]
                    val b = theirs[key]
                    val outcome = when {
                        a == null || b == null -> PRESENCE
                        type == Rs3Anims.ROTATE -> {
                            if (Math.floorMod(a - b, TURN) == 0) {
                                rotationsExactTurn++
                                if (a == b) {
                                    rotationsExactInt++
                                }
                                EXACT
                            } else {
                                DIFFER
                            }
                        }
                        a == b -> EXACT
                        else -> DIFFER
                    }
                    tally.getOrPut("type %2d axis %d".format(type, axis)) { IntArray(3) }[outcome]++
                    if (flagsSame) {
                        sameFlags[outcome]++
                    }
                    if (outcome != EXACT) {
                        frameExact = false
                        if (result.differences.size < DIFFERENCES) {
                            result.differences.add("frame ${frame.file} transform $transform type $type axis $axis: shipped $b, converted $a")
                        }
                    }
                }
                result.frames++
                if (frameExact) {
                    result.exact++
                }
            }
        }
        println("archive pairs ${pairs.size} (from ${pairs.values.sum()} seqs with equal durations and frame file ids)")
        println("component   exact    differ   presence")
        for ((key, counts) in tally) {
            val total = counts.sum()
            println("  $key  %6d %6d %6d  %s".format(counts[EXACT], counts[DIFFER], counts[PRESENCE], percent(counts[EXACT], total)))
        }
        println("frames whose flag bytes are identical: ${percent(sameFlags[EXACT], sameFlags.sum())} of ${sameFlags.sum()} components exact")
        println("rotations equal mod 4096: $rotationsExactTurn, of which equal as integers: $rotationsExactInt")
        val comparable = results.filter { it.compared > 0 }
        val exact = comparable.filter { it.exact == it.frames }
        println("pairs with a comparable base ${comparable.size}, fully exact ${exact.size}, residual ${comparable.size - exact.size}, incomparable ${results.size - comparable.size}")
        for (result in comparable.filter { it.exact != it.frames }) {
            println("  727 ${result.source} / rs3 ${result.rs3}: ${result.exact} of ${result.frames} frames exact")
            for (difference in result.differences) {
                println("      $difference")
            }
        }
        bases(rs3Tree, sourceTree)
    }

    private fun padded(a: IntArray, b: IntArray): Boolean {
        for (index in 0 until maxOf(a.size, b.size)) {
            if (a.getOrElse(index) { 0 } != b.getOrElse(index) { 0 }) {
                return false
            }
        }
        return true
    }

    private fun pairs(rs3Tree: Path, sourceTree: Path): Map<Pair<Int, Int>, Int> {
        val pairs = LinkedHashMap<Pair<Int, Int>, Int>()
        val ids = Files.list(sourceTree.resolve("config_seq")).use { stream ->
            stream.toList().mapNotNull { it.fileName.toString().removeSuffix(".json").toIntOrNull() }.sorted()
        }
        for (id in ids) {
            val theirs = rs3Tree.resolve("config_seq").resolve("$id.json")
            if (!Files.exists(theirs)) {
                continue
            }
            val shipped = SourceJsonText.read(sourceTree.resolve("config_seq").resolve("$id.json"))
            val rs3 = SourceJsonText.read(theirs)
            val frames = ints(shipped, "frames") ?: continue
            val rs3Frames = ints(rs3, "frames") ?: continue
            if (frames.size != rs3Frames.size || !ints(shipped, "durations").contentEquals(ints(rs3, "durations"))) {
                continue
            }
            if (frames.indices.any { frames[it] and MASK != rs3Frames[it] and MASK }) {
                continue
            }
            val archive = frames.map { it ushr AnimationFrameDecoder.BITS }.distinct().singleOrNull() ?: continue
            val rs3Archive = rs3Frames.map { it ushr AnimationFrameDecoder.BITS }.distinct().singleOrNull() ?: continue
            if (!Files.exists(sourceTree.resolve("anims").resolve("$archive.glb")) || !Rs3Anims.exists(rs3Tree, rs3Archive)) {
                continue
            }
            pairs.merge(archive to rs3Archive, 1, Int::plus)
        }
        return pairs
    }

    private fun shipped(tree: Path, archive: Int): List<ShippedFrame> {
        val root = Gltf.root(Gltf.chunks(Files.readAllBytes(tree.resolve("anims").resolve("$archive.glb"))).first)
        val rs = root["scenes"]!!.jsonArray[0].jsonObject["extras"]!!.jsonObject["rs"]!!.jsonObject
        val base = rs["base"]!!.jsonPrimitive.int
        return rs["frames"]!!.jsonArray.map { element ->
            val frame = element.jsonObject
            val flags = IntArray(frame["count"]!!.jsonPrimitive.int)
            val moved = Gltf.ints(frame["transforms"]) ?: IntArray(0)
            val bytes = Gltf.ints(frame["flags"]) ?: IntArray(0)
            for (index in moved.indices) {
                flags[moved[index]] = bytes[index]
            }
            ShippedFrame(frame["file"]!!.jsonPrimitive.int, frame["base"]?.jsonPrimitive?.int ?: base, flags, Gltf.ints(frame["values"]) ?: IntArray(0))
        }
    }

    private fun baseTypes(tree: Path, id: Int): IntArray = ints(SourceJsonText.read(tree.resolve("bases").resolve("$id.json")), "types") ?: IntArray(0)

    private fun bases(rs3Tree: Path, sourceTree: Path) {
        var compared = 0
        var typesEqual = 0
        var equal = 0
        var skeletal = 0
        val fields = IntArray(3)
        val mismatches = ArrayList<String>()
        val ids = Files.list(sourceTree.resolve("bases")).use { stream ->
            stream.toList().mapNotNull { it.fileName.toString().removeSuffix(".json").toIntOrNull() }.sorted()
        }
        for (id in ids) {
            if (!Rs3Anims.baseExists(rs3Tree, id)) {
                continue
            }
            val rs3 = Rs3Anims.base(rs3Tree, id)
            compared++
            val converted = try {
                Rs3Anims.convert(rs3, id)
            } catch (e: IllegalStateException) {
                skeletal++
                continue
            }
            val shipped = SourceJsonText.read(sourceTree.resolve("bases").resolve("$id.json"))
            if (!converted.types.contentEquals(ints(shipped, "types"))) {
                continue
            }
            typesEqual++
            val shadowed = converted.shadowed.contentEquals(ints(shipped, "shadowed"))
            val submeshes = converted.submeshes.contentEquals(ints(shipped, "submeshes"))
            val shippedLabels = shipped["labels"]!!.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } }
            val labels = converted.labels.map { it?.toList() ?: emptyList() } == shippedLabels
            listOf(shadowed, submeshes, labels).forEachIndexed { index, same ->
                if (same) {
                    fields[index]++
                }
            }
            if (shadowed && submeshes && labels) {
                equal++
            } else if (mismatches.size < DIFFERENCES) {
                mismatches.add("base $id: shadowed $shadowed, submeshes $submeshes, labels $labels")
            }
        }
        println("bases in both trees $compared: ${compared - skeletal} label bases, types equal on $typesEqual")
        println("  of those: shadowed = unusedA ${fields[0]}, submeshes = unusedB ${fields[1]}, labels ${fields[2]}, all equal $equal")
        mismatches.forEach { println("  $it") }
    }

    private fun ints(json: JsonObject, key: String): IntArray? {
        val values = json[key]?.jsonArray ?: return null
        return IntArray(values.size) { values[it].jsonPrimitive.int }
    }

    private const val MASK = (1 shl AnimationFrameDecoder.BITS) - 1

    private const val DIFFERENCES = 4
}
