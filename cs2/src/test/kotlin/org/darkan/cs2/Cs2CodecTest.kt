package org.darkan.cs2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The codec is the floor the whole toolchain stands on: if index 12 does not
 * decode and re-encode to the same bytes, nothing above it can be trusted. The
 * whole-cache proof is `cs2 verify` (6567/6567 byte-identical); this covers the
 * format's awkward corners without needing a cache to be present.
 */
class Cs2CodecTest {

    private fun script(
        name: String?,
        instructions: List<Cs2Instruction>,
        switchTables: List<List<Cs2SwitchCase>> = emptyList(),
    ) = Cs2Script(
        name = name,
        instructions = instructions,
        intLocalsCount = 3,
        stringLocalsCount = 2,
        longLocalsCount = 1,
        intArgsCount = 2,
        stringArgsCount = 1,
        longArgsCount = 0,
        switchTables = switchTables,
    )

    @Test
    fun `every operand width round-trips`() {
        // One instruction of each operand encoding: a string, a long, a large
        // (4-byte) operand and a plain 1-byte one.
        val original = script(
            name = "test_script",
            instructions = listOf(
                Cs2Instruction(Cs2Op.PUSH_CONSTANT_STRING, strOperand = "hello \"world\""),
                Cs2Instruction(Cs2Op.PUSH_LONG_CONSTANT, longOperand = -1234567890123L),
                Cs2Instruction(Cs2Op.PUSH_CONSTANT_INT, intOperand = Int.MIN_VALUE),
                Cs2Instruction(Cs2Op.RETURN, intOperand = 0),
            ),
        )

        val bytes = Cs2Codec.encode(original)
        val decoded = Cs2Codec.decode(bytes)

        assertEquals("test_script", decoded.name)
        assertEquals(original.instructions, decoded.instructions)
        assertEquals(2, decoded.intArgsCount)
        assertEquals(1, decoded.stringArgsCount)
        assertEquals(3, decoded.intLocalsCount)
        assertTrue(bytes.contentEquals(Cs2Codec.encode(decoded)), "re-encode is not byte-identical")
    }

    @Test
    fun `switch table order is preserved`() {
        val tables = listOf(
            listOf(Cs2SwitchCase(0, 4), Cs2SwitchCase(-1, 9), Cs2SwitchCase(Int.MAX_VALUE, 2)),
            listOf(Cs2SwitchCase(7, 1)),
        )
        val original = script(
            name = null,
            instructions = listOf(Cs2Instruction(Cs2Op.RETURN)),
            switchTables = tables,
        )

        val bytes = Cs2Codec.encode(original)
        val decoded = Cs2Codec.decode(bytes)

        assertEquals(tables, decoded.switchTables)
        assertTrue(bytes.contentEquals(Cs2Codec.encode(decoded)))
    }

    @Test
    fun `an absent name decodes as null`() {
        // A bare 0 byte and an empty string are the same bytes on the wire, so an
        // empty name has to come back as null or re-encoding would not be exact.
        val bytes = Cs2Codec.encode(script("", listOf(Cs2Instruction(Cs2Op.RETURN))))
        assertNull(Cs2Codec.decode(bytes).name)
    }

    @Test
    fun `the opcode table is complete and self-consistent`() {
        assertEquals(1010, Cs2Op.entries.size)
        for (entry in Cs2Op.entries) {
            assertEquals(entry, Cs2Op[entry.id], "id lookup disagrees for $entry")
            assertEquals(entry, Cs2Op.byTsName(entry.tsName), "TS name lookup disagrees for $entry")
        }
    }
}
