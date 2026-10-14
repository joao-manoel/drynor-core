package org.darkan.cs2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How a source file says which script it is. The binding is what the cache source
 * tree packer resolves an id through, so a file that could be read two ways - or
 * silently filed under the wrong archive - is the failure that matters here.
 */
class Cs2LayoutTest {

    @Test
    fun `the numeric header of an older dump still binds`() {
        val source = "// clientscript 1620\nfunction cs2_1620(): void {}\n"
        assertEquals(1620, Cs2Layout.scriptIdOf(source, "proc/somewhere_else.ts"))
    }

    @Test
    fun `a header that names nothing does not fall through to the path`() {
        // Falling back to the path here would compile the file into whichever
        // archive it happened to be filed under - the silent mis-binding the
        // layout has to rule out.
        val source = "// [proc,not_a_real_script_name_at_all]\nfunction f(): void {}\n"
        assertNull(Cs2Layout.scriptIdOf(source, "proc/quicksort.ts"))
    }

    @Test
    fun `other comment lines are not read as a binding`() {
        val source = """
            /// <reference path="../cs2.d.ts" />
            // name: something with spaces
            // A prose comment.
            function f(): void {}
        """.trimIndent()
        assertNull(Cs2Layout.scriptIdOf(source, null))
    }

    @Test
    fun `declaration files are not scripts`() {
        assertEquals(true, Cs2Layout.isScriptFile(File("proc/quicksort.ts")))
        assertEquals(false, Cs2Layout.isScriptFile(File("gamevals.d.ts")))
        assertEquals(false, Cs2Layout.isScriptFile(File("tsconfig.json")))
    }
}
