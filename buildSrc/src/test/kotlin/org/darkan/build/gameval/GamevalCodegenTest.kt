package org.darkan.build.gameval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the generator emits for a well-formed catalog. */
class GamevalCodegenTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun catalogs(): File = temp.newFolder("gamevals")

    @Test
    fun `every id gets a constant, placeholders included, in ascending id order`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("0" to "loc_0", "1" to "tree", "2" to "loc_2", "7" to "gate"))
        val source = Fixtures.generate(dir, mapOf("loc" to "Loc")).getValue("Loc.java")
        assertEquals(listOf("loc_0" to 0, "tree" to 1, "loc_2" to 2, "gate" to 7), Fixtures.constants(source))
        assertTrue(source.contains("public final class Loc {"))
        assertTrue(source.contains("4 ids: 2 named, 2 placeholder; every id has a constant"))
    }

    @Test
    fun `named-ids-only mode skips placeholders`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("0" to "loc_0", "1" to "tree"))
        val source = Fixtures.generate(dir, mapOf("loc" to "Loc"), includePlaceholders = false).getValue("Loc.java")
        assertEquals(listOf("tree" to 1), Fixtures.constants(source))
        assertTrue(source.contains("1 named ids of 2; placeholders are skipped"))
    }

    @Test
    fun `identifiers are escaped by the documented rules and the rewrite is recorded`() {
        val dir = catalogs()
        Fixtures.catalog(
            dir,
            "loc",
            mapOf("1" to "2h_sword", "2" to "big-tree", "3" to "class", "4" to "val", "5" to "---"),
        )
        val source = Fixtures.generate(dir, mapOf("loc" to "Loc")).getValue("Loc.java")
        assertEquals(
            listOf("_2h_sword" to 1, "big_tree" to 2, "class_" to 3, "val_" to 4, "_5" to 5),
            Fixtures.constants(source),
        )
        assertTrue(source.contains("loc 1 \"2h_sword\" -> _2h_sword (leading digit)"))
        assertTrue(source.contains("loc 2 \"big-tree\" -> big_tree (illegal character(s) replaced by _)"))
        assertTrue(source.contains("loc 3 \"class\" -> class_ (reserved word)"))
        assertTrue(source.contains("underscores only"))
    }

    @Test
    fun `clientscripts drop the category unless the bare name is in more than one`() {
        val dir = catalogs()
        Fixtures.catalog(
            dir,
            "clientscript",
            mapOf(
                "1" to "[proc,quicksort]",
                "2" to "[proc,scrollbar]",
                "3" to "[clientscript,scrollbar]",
                "4" to "cs2_4",
            ),
        )
        val source = Fixtures.generate(dir, mapOf("clientscript" to "Cs2")).getValue("Cs2.java")
        assertEquals(
            listOf("quicksort" to 1, "proc_scrollbar" to 2, "clientscript_scrollbar" to 3, "cs2_4" to 4),
            Fixtures.constants(source),
        )
        assertTrue(source.contains("// 1 [proc,quicksort]"))
    }

    @Test
    fun `components pack the interface id and expose IFACE, named or not`() {
        val dir = catalogs()
        Fixtures.catalog(
            dir,
            "component",
            mapOf("679:0" to "inventory:inv", "679:1" to "inventory:IFACE", "12:3" to "component_12_3"),
        )
        val source = Fixtures.generate(dir, mapOf("component" to "Component")).getValue("Component.java")
        assertTrue(source.contains("public static final class interface_12 {"))
        assertTrue(source.contains("public static final class inventory {"))
        assertTrue(source.contains("public static final int IFACE = 679;"))
        assertTrue(source.contains("public static final int IFACE = 12;"))
        assertTrue(source.contains("public static final int inv = ${(679 shl 16) or 0}; // 679:0 inventory:inv"))
        // A component actually named IFACE cannot shadow the interface id member.
        assertTrue(source.contains("public static final int IFACE_ = ${(679 shl 16) or 1};"))
        assertTrue(source.contains("public static final int component_12_3 = ${(12 shl 16) or 3}; // 12:3"))
    }

    @Test
    fun `a type larger than the shard size continues into a package-private superclass chain`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", (0..4).associate { it.toString() to "loc$it" })
        val files = Fixtures.generate(dir, mapOf("loc" to "Loc"), shardSize = 2)
        assertEquals(setOf("Loc.java", "Loc2.java", "Loc3.java"), files.keys)
        assertTrue(files.getValue("Loc.java").contains("public final class Loc extends Loc2 {"))
        assertTrue(files.getValue("Loc2.java").contains("class Loc2 extends Loc3 {"))
        assertFalse(files.getValue("Loc2.java").contains("public final class"))
        assertTrue(files.getValue("Loc3.java").contains("class Loc3 {"))
        assertTrue(files.getValue("Loc3.java").contains("Continuation of {@link Loc}"))
        assertEquals(listOf("loc0" to 0, "loc1" to 1), Fixtures.constants(files.getValue("Loc.java")))
        assertEquals(listOf("loc4" to 4), Fixtures.constants(files.getValue("Loc3.java")))
        assertTrue(files.getValue("Loc.java").contains("Part 1 of 3 (ids 0..1); the class chain Loc -> Loc2 -> Loc3"))
    }

    @Test
    fun `a shard that is no longer needed is removed`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", (0..4).associate { it.toString() to "loc$it" })
        assertEquals(3, Fixtures.generate(dir, mapOf("loc" to "Loc"), shardSize = 2).size)
        assertEquals(setOf("Loc.java"), Fixtures.generate(dir, mapOf("loc" to "Loc"), shardSize = 32000).keys)
    }

    @Test
    fun `generation is deterministic`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", (0..50).associate { it.toString() to if (it % 3 == 0) "loc_$it" else "loc_name$it" })
        assertEquals(Fixtures.generate(dir, mapOf("loc" to "Loc")), Fixtures.generate(dir, mapOf("loc" to "Loc")))
    }
}
