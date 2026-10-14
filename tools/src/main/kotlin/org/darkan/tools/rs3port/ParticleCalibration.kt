package org.darkan.tools.rs3port

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

object ParticleCalibration {

    private val SKIP_PRODUCER = setOf("id", "opcodeOrder", "shadowedPayloads", "materialId")

    private val SKIP_EFFECTOR = setOf("id", "opcodeOrder", "shadowedPayloads")

    private val identity = ParticleIds({ it }, { it }, { it })

    fun run(rs3Tree: Path, sourceTree: Path) {
        producers(rs3Tree, sourceTree)
        effectors(rs3Tree, sourceTree)
        println("producer material votes (RS3 unknown15 -> 727 materialId):")
        for ((rs3, targets) in materialVotes(rs3Tree, sourceTree).toSortedMap()) {
            val verdict = if (targets.size == 1) "unanimous" else "SPLIT"
            println("  rs3 $rs3 -> ${targets.entries.sortedByDescending { it.value }.joinToString { "${it.key}x${it.value}" }} $verdict")
        }
    }

    private fun producers(rs3Tree: Path, sourceTree: Path) {
        val tally = FieldTally()
        var total = 0
        var equal = 0
        val failures = ArrayList<String>()
        val unequal = ArrayList<String>()
        for (id in ids(sourceTree, "producer")) {
            if (!Rs3Particles.producerExists(rs3Tree, id)) {
                continue
            }
            total++
            val converted = try {
                Rs3Particles.producer(Rs3Particles.producerJson(rs3Tree, id), id, identity)
            } catch (e: IllegalArgumentException) {
                failures.add("$id: ${e.message}")
                continue
            }
            val mine = DefinitionFields.definition(converted, SKIP_PRODUCER)
            val shipped = DefinitionFields.json(SourceJsonText.read(path(sourceTree, "producer", id)), mine.keys)
            val differing = tally.add(id, mine, shipped)
            if (differing.isEmpty()) {
                equal++
            } else {
                unequal.add("$id $differing")
            }
        }
        println("producers in both trees $total: fully equal (materialId aside) $equal, unconvertible ${failures.size}")
        tally.report()
        failures.forEach { println("  unconvertible $it") }
        println("producers that differ:")
        unequal.forEach { println("  $it") }
    }

    private fun effectors(rs3Tree: Path, sourceTree: Path) {
        val tally = FieldTally()
        var total = 0
        var equal = 0
        for (id in ids(sourceTree, "particle")) {
            if (!Rs3Particles.effectorExists(rs3Tree, id)) {
                continue
            }
            total++
            val mine = DefinitionFields.definition(Rs3Particles.effector(Rs3Particles.effectorJson(rs3Tree, id), id), SKIP_EFFECTOR)
            val shipped = DefinitionFields.json(SourceJsonText.read(path(sourceTree, "particle", id)), mine.keys)
            if (tally.add(id, mine, shipped).isEmpty()) {
                equal++
            }
        }
        println("effectors in both trees $total: fully equal $equal")
        tally.report()
    }

    fun materialVotes(rs3Tree: Path, sourceTree: Path): Map<Int, Map<Int, Int>> {
        val votes = HashMap<Int, HashMap<Int, Int>>()
        for (id in ids(sourceTree, "producer")) {
            if (!Rs3Particles.producerExists(rs3Tree, id)) {
                continue
            }
            val rs3 = Rs3Particles.producerJson(rs3Tree, id)["unknown15"]?.jsonPrimitive?.int ?: continue
            val shipped = SourceJsonText.read(path(sourceTree, "producer", id))["materialId"]?.jsonPrimitive?.int ?: -1
            votes.getOrPut(rs3) { HashMap() }.merge(shipped, 1, Int::plus)
        }
        return votes
    }

    private fun ids(tree: Path, directory: String): List<Int> =
        Files.list(tree.resolve("config_particle").resolve(directory)).use { stream ->
            stream.toList().mapNotNull { it.fileName.toString().removeSuffix(".json").toIntOrNull() }.sorted()
        }

    private fun path(tree: Path, directory: String, id: Int): Path =
        tree.resolve("config_particle").resolve(directory).resolve("$id.json")
}
