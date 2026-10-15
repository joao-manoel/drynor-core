package org.drynor.tools.rs3port

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

object MaterialCalibration {

    private val SKIP = setOf("id")

    fun run(rs3Tree: Path, sourceTree: Path, crosswalk: Crosswalk?) {
        val votes = HashMap<Int, HashMap<Int, Int>>()
        merge(votes, ParticleCalibration.materialVotes(rs3Tree, sourceTree))
        merge(votes, billboardVotes(rs3Tree, sourceTree))
        crosswalk?.let { table -> merge(votes, table.counts.filterKeys { it !in table.overrides }) }
        val pairs = votes.filter { it.value.size == 1 && it.value.keys.single() >= 0 }.mapValues { it.value.keys.single() }
        val split = votes.filter { it.value.size > 1 }
        println("RS3 -> 727 material pairs: ${pairs.size} unanimous, ${split.size} split")
        split.toSortedMap().forEach { (rs3, targets) -> println("  split rs3 $rs3 -> $targets") }
        val tally = FieldTally()
        var compared = 0
        var equal = 0
        for ((rs3, target) in pairs.toSortedMap()) {
            val shippedPath = sourceTree.resolve("materials").resolve("$target.json")
            if (!Rs3Materials.exists(rs3Tree, rs3) || !Files.exists(shippedPath)) {
                continue
            }
            val converted = try {
                Rs3Materials.convert(Rs3Materials.json(rs3Tree, rs3), target)
            } catch (e: IllegalArgumentException) {
                println("  unconvertible rs3 $rs3: ${e.message}")
                continue
            }
            compared++
            val mine = DefinitionFields.definition(converted, SKIP)
            if (tally.add(rs3, mine, DefinitionFields.json(SourceJsonText.read(shippedPath), mine.keys)).isEmpty()) {
                equal++
            }
        }
        println("materials compared $compared, every field equal on $equal (examples are rs3 ids)")
        tally.report()
    }

    fun billboardVotes(rs3Tree: Path, sourceTree: Path): Map<Int, Map<Int, Int>> {
        val votes = HashMap<Int, HashMap<Int, Int>>()
        val ids = Files.list(sourceTree.resolve("config_billboard")).use { stream ->
            stream.toList().mapNotNull { it.fileName.toString().removeSuffix(".json").toIntOrNull() }
        }
        for (id in ids) {
            val theirs = rs3Tree.resolve("config_billboard").resolve("$id.json")
            if (!Files.exists(theirs)) {
                continue
            }
            val rs3 = SourceJsonText.read(theirs)["material"]?.jsonPrimitive?.int ?: continue
            val shipped = SourceJsonText.read(sourceTree.resolve("config_billboard").resolve("$id.json"))["materialId"]!!.jsonPrimitive.int
            votes.getOrPut(rs3) { HashMap() }.merge(shipped, 1, Int::plus)
        }
        return votes
    }

    private fun merge(into: HashMap<Int, HashMap<Int, Int>>, from: Map<Int, Map<Int, Int>>) {
        for ((rs3, targets) in from) {
            val bucket = into.getOrPut(rs3) { HashMap() }
            for ((target, count) in targets) {
                bucket.merge(target, count, Int::plus)
            }
        }
    }
}
