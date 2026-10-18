package org.drynor.cs2

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.drynor.cs2.compile.Cs2Parser
import org.drynor.cs2.ir.Cs2Type

/**
 * The tree-derived compile context, without a cache anywhere.
 *
 * [Cs2SourceContext] is what lets a server build a cache from a source tree whose clientscripts
 * changed: the facts the corpus analysis recovers from bytecode are all written out in the
 * TypeScript, so a folder answers them for itself. These are the readings, one per fact.
 * [org.drynor.cs2.source.Cs2SourceCodecTest] is the proof that they add up to the cache's own
 * bytes.
 */
class Cs2SourceContextTest {
    @Test
    fun `clan member sorting consumes the requested slot and returns its member index`() {
        val folder = Files.createTempDirectory("cs2-clan-sort").toFile()
        try {
            val file = Cs2Layout.fileFor(folder, 0)
            file.parentFile?.mkdirs()
            for (op in listOf(Cs2Op.ACTIVECLANSETTINGS_GETSORTEDAFFINEDSLOT, Cs2Op.ACTIVECLANCHANNEL_GETSORTEDUSERSLOT)) {
                file.writeText("// ${Cs2Layout.headerOf(0)}\nfunction f(slot: number): number {\n return ${op.tsName}(slot);\n}\n")
                val script = Cs2Codec.decode(Cs2Compiler.fromSources(folder).compile(file))
                val sort = script.instructions.indexOfFirst { it.op == op }
                assertTrue(sort > 0)
                assertEquals(Cs2Op.PUSH_INT_LOCAL, script.instructions[sort - 1].op)
                assertEquals(Cs2Op.RETURN, script.instructions[sort + 1].op)
                assertEquals(1, op.popInt)
                assertEquals(1, op.pushInt)
            }
        } finally {
            folder.deleteRecursively()
        }
    }


    @Test
    fun `a signature is read without parsing the body`() {
        val signature = Cs2Parser.signature(
            """
            // [proc,example]

            function example(intArg0: number, strArg0: string, intArg1: component): [string, number] {
                nothing here has to parse: <<<
            }
            """.trimIndent()
        )
        assertEquals("example", signature?.name)
        assertEquals(
            listOf(Cs2Type.INT, Cs2Type.STRING, Cs2Type.INT),
            signature?.arguments,
            "arguments come back in declaration order, which can interleave the stacks"
        )
        assertEquals(listOf(Cs2Type.STRING, Cs2Type.INT), signature?.returns)
    }

    @Test
    fun `void and single returns read as the lists they are`() {
        assertEquals(emptyList(), Cs2Parser.signature("function a(): void {\n}")?.returns)
        assertEquals(listOf(Cs2Type.INT), Cs2Parser.signature("function a(): number {\n}")?.returns)
        assertEquals(listOf(Cs2Type.LONG), Cs2Parser.signature("function a(): bigint {\n}")?.returns)
    }

    @Test
    fun `a file with no function declares no signature`() {
        assertNull(Cs2Parser.signature("// just a comment\n"))
        assertNull(Cs2Parser.signature("declare const x: number;\n"))
    }

    /**
     * The header is read even when the catalogs no longer have the name it claims, because the
     * signature is a different question from which archive the file belongs to - see
     * [Cs2Parser.Companion.signature].
     */
    @Test
    fun `a signature is read from a file whose header names no script`() {
        val signature = Cs2Parser.signature("// [proc,nothing_is_called_this]\n\nfunction f(): number {\n}")
        assertEquals(listOf(Cs2Type.INT), signature?.returns)
    }

    @Test
    fun `the string params declaration round trips`() {
        val ids = setOf(8, 3, 4096)
        val declaration = Cs2Declarations.stringParams(ids)
        assertEquals(setOf(3, 8, 4096), Cs2Declarations.readStringParams(declaration))
        assertEquals(
            emptySet(),
            Cs2Declarations.readStringParams(Cs2Declarations.stringParams(emptySet())),
            "an empty declaration is still an answer: no param yields a string"
        )
        assertNull(
            Cs2Declarations.readStringParams("// nothing about params here\n"),
            "a folder written before the declaration existed says nothing, which is not the same"
        )
        assertTrue(Cs2Declarations.generate(stringParams = ids).contains(declaration.trim()))
    }

    @Test
    fun `a folder answers for its own scripts`() {
        val folder = Files.createTempDirectory("cs2-context").toFile()
        try {
            val callee = 0
            val caller = 1
            write(folder, callee, "callee(strArg0: string): [number, string]")
            write(folder, caller, "caller(): void")
            val context = Cs2SourceContext.read(folder)

            assertEquals(2, context.size)
            assertEquals(
                listOf(Cs2Type.INT, Cs2Type.STRING),
                context.returnTypes(callee),
                "the callee's own annotation is its push order"
            )
            val header = context.script(callee)
            assertEquals(ReturnSignature(1, 1, 0), header?.returnSignature)
            assertEquals(0, header?.intArgsCount)
            assertEquals(1, header?.stringArgsCount)
            assertEquals(emptyList(), context.returnTypes(caller))
            assertNull(context.script(9_999), "a script the folder has no file for is not there")

            assertFalse(
                context.declaresStringParams,
                "there is no cs2.d.ts here, so nothing has been said about params"
            )
            assertFalse(context.paramIsString(8))

            folder.resolve(Cs2Declarations.OPCODES_FILE)
                .writeText(Cs2Declarations.stringParams(setOf(8)))
            val declared = Cs2SourceContext.read(folder)
            assertTrue(declared.declaresStringParams)
            assertTrue(declared.paramIsString(8))
            assertFalse(declared.paramIsString(9))
        } finally {
            folder.deleteRecursively()
        }
    }

    /** A script at the path its gameval name puts it, with the header that binds it. */
    private fun write(folder: File, scriptId: Int, declaration: String) {
        val file = Cs2Layout.fileFor(folder, scriptId)
        file.parentFile?.mkdirs()
        file.writeText("// ${Cs2Layout.headerOf(scriptId)}\n\nfunction $declaration {\n}\n")
    }
}
