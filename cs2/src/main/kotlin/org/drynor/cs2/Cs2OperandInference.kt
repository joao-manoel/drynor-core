package org.drynor.cs2

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache

/**
 * Recovers operand widths for opcodes the reference client no longer defines.
 *
 * The deobfuscated client enumerates 962 of the 1006 opcodes this cache uses;
 * the rest were dropped along the way. Their operand width is still recoverable
 * from the files themselves: a script's instruction stream must consume exactly
 * the bytes between the name and the header, and yield exactly the instruction
 * count the header declares. Where one width fits and the other does not, the
 * width is proven.
 *
 * The solver propagates: each pass fixes whatever is unambiguous, which shrinks
 * the search space for scripts that use several unknown opcodes at once.
 */
object Cs2OperandInference {

    /** Operand encodings an unknown opcode could plausibly use. */
    enum class Width(val bytes: Int) { BYTE(1), INT(4) }

    data class Result(
        val resolved: Map<Int, Width>,
        val ambiguous: Map<Int, Set<Width>>,
        val occurrences: Map<Int, Int>,
        val unsolvedScripts: List<Int>,
    )

    fun infer(cache: Cache, log: (String) -> Unit = {}): Result {
        val scripts = Cs2Cache(cache)
        val pending = ArrayList<Pair<Int, ByteArray>>()
        val occurrences = HashMap<Int, Int>()

        for (id in scripts.scriptIds()) {
            val raw = try {
                scripts.raw(id) ?: continue
            } catch (e: Exception) {
                continue
            }
            if (!usesUnknownOpcode(raw)) continue
            pending.add(id to raw)
        }
        log("${pending.size} scripts use at least one undefined opcode")

        // Every opcode starts out able to take either width.
        val candidates = HashMap<Int, MutableSet<Width>>()

        var pass = 0
        while (true) {
            pass++
            var progress = false
            val perScript = HashMap<Int, MutableSet<Width>>()
            val stillUnsolved = ArrayList<Int>()

            for ((id, raw) in pending) {
                val solutions = solve(raw, candidates)
                if (solutions.isEmpty()) {
                    stillUnsolved.add(id)
                    continue
                }
                // Widths that appear in at least one valid parse of this script.
                val union = HashMap<Int, MutableSet<Width>>()
                for (solution in solutions) {
                    for ((op, width) in solution) {
                        union.getOrPut(op) { HashSet() }.add(width)
                    }
                }
                for ((op, widths) in union) {
                    val known = perScript[op]
                    if (known == null) {
                        perScript[op] = widths
                    } else {
                        known.retainAll(widths)
                    }
                }
            }

            for ((op, widths) in perScript) {
                val existing = candidates[op]
                if (existing == null) {
                    candidates[op] = widths
                    progress = true
                } else if (existing.retainAll(widths)) {
                    progress = true
                }
            }

            val undecided = candidates.count { it.value.size > 1 }
            log("pass $pass: ${candidates.size} opcodes seen, $undecided still ambiguous, " +
                "${stillUnsolved.size} scripts unsolved")
            if (!progress || undecided == 0) {
                for ((id, raw) in pending) countOccurrences(raw, candidates, occurrences)
                return Result(
                    resolved = candidates.filterValues { it.size == 1 }.mapValues { it.value.first() },
                    ambiguous = candidates.filterValues { it.size != 1 },
                    occurrences = occurrences,
                    unsolvedScripts = stillUnsolved,
                )
            }
            if (pass > 20) {
                for ((id, raw) in pending) countOccurrences(raw, candidates, occurrences)
                return Result(
                    resolved = candidates.filterValues { it.size == 1 }.mapValues { it.value.first() },
                    ambiguous = candidates.filterValues { it.size != 1 },
                    occurrences = occurrences,
                    unsolvedScripts = stillUnsolved,
                )
            }
        }
    }

    private const val FIXED_HEADER_BYTES = 16
    private const val TRAILER_BYTES = 2

    private fun bounds(raw: ByteArray): Pair<Int, Int>? {
        if (raw.size <= TRAILER_BYTES + FIXED_HEADER_BYTES) return null
        val reader = BufferReader(raw)
        reader.position(raw.size - TRAILER_BYTES)
        val switchBlockSize = reader.readUnsignedShort()
        val headerStart = raw.size - TRAILER_BYTES - switchBlockSize - FIXED_HEADER_BYTES
        if (headerStart < 1 || headerStart >= raw.size) return null
        reader.position(headerStart)
        val instructionCount = reader.readInt()
        if (instructionCount < 0 || instructionCount > headerStart) return null
        return headerStart to instructionCount
    }

    private fun instructionStart(raw: ByteArray): Int {
        if (raw.isNotEmpty() && raw[0] == 0.toByte()) return 1
        var i = 0
        while (i < raw.size && raw[i] != 0.toByte()) i++
        return i + 1
    }

    private fun usesUnknownOpcode(raw: ByteArray): Boolean {
        val (headerStart, _) = bounds(raw) ?: return false
        var at = instructionStart(raw)
        // A single unknown opcode desynchronises the walk, so this is only a
        // cheap pre-filter: anything that fails to parse cleanly is a candidate.
        while (at + 2 <= headerStart) {
            val opcodeId = ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
            val op = Cs2Op.find(opcodeId) ?: return true
            at += 2 + operandBytes(op, raw, at + 2)
        }
        return at != headerStart
    }

    private fun operandBytes(op: Cs2Op, raw: ByteArray, at: Int): Int = when {
        op == Cs2Op.PUSH_CONSTANT_STRING -> {
            var i = at
            while (i < raw.size && raw[i] != 0.toByte()) i++
            i - at + 1
        }
        op == Cs2Op.PUSH_LONG_CONSTANT -> 8
        op.largeOperand -> 4
        else -> 1
    }

    /**
     * All assignments of widths to the unknown opcodes in this script that make
     * the instruction stream land exactly on the header with the declared count.
     * Bounded so a pathological file cannot blow up the search.
     */
    private fun solve(raw: ByteArray, known: Map<Int, Set<Width>>): List<Map<Int, Width>> {
        val (headerStart, instructionCount) = bounds(raw) ?: return emptyList()
        val start = instructionStart(raw)
        val solutions = ArrayList<Map<Int, Width>>()
        val assignment = HashMap<Int, Width>()
        var explored = 0

        fun walk(at: Int, count: Int) {
            if (solutions.size >= 8 || explored > 200_000) return
            explored++
            if (at == headerStart) {
                if (count == instructionCount) solutions.add(HashMap(assignment))
                return
            }
            if (at > headerStart || count > instructionCount || at + 2 > headerStart) return

            val opcodeId = ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
            val op = Cs2Op.find(opcodeId)
            if (op != null) {
                walk(at + 2 + operandBytes(op, raw, at + 2), count + 1)
                return
            }

            val fixed = assignment[opcodeId]
            val options = when {
                fixed != null -> listOf(fixed)
                else -> (known[opcodeId] ?: Width.entries.toSet()).toList()
            }
            for (width in options) {
                val fresh = fixed == null
                if (fresh) assignment[opcodeId] = width
                walk(at + 2 + width.bytes, count + 1)
                if (fresh) assignment.remove(opcodeId)
            }
        }

        walk(start, 0)
        return solutions
    }

    private fun countOccurrences(
        raw: ByteArray,
        known: Map<Int, Set<Width>>,
        into: HashMap<Int, Int>,
    ) {
        val solutions = solve(raw, known)
        val solution = solutions.singleOrNull() ?: solutions.firstOrNull() ?: return
        val (headerStart, _) = bounds(raw) ?: return
        var at = instructionStart(raw)
        while (at + 2 <= headerStart) {
            val opcodeId = ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
            val op = Cs2Op.find(opcodeId)
            if (op != null) {
                at += 2 + operandBytes(op, raw, at + 2)
            } else {
                into[opcodeId] = (into[opcodeId] ?: 0) + 1
                at += 2 + (solution[opcodeId] ?: Width.BYTE).bytes
            }
        }
    }
}
