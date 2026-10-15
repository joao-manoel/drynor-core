package org.drynor.build.gameval

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every catalog defect that must stop the build, proven against the real Gradle task: the task
 * action throwing is what fails `./gradlew build`.
 *
 * Each case asserts the message names the type, the offending ids and the offending strings,
 * because that message is the whole repair instruction a developer gets.
 */
class GamevalValidationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun catalogs(): File = temp.newFolder("gamevals")

    /** Runs the task and returns the failure message, failing the test if it succeeded. */
    private fun failureOf(dir: File, classes: Map<String, String>): String {
        try {
            Fixtures.generate(dir, classes)
        } catch (e: GamevalGenerationException) {
            return e.message.orEmpty()
        }
        fail("expected the build to fail, but the constants generated cleanly")
        return ""
    }

    private fun assertMentions(message: String, vararg needles: String) {
        for (needle in needles) {
            assertTrue("message should name '$needle' but was:\n$message", message.contains(needle))
        }
    }

    @Test
    fun `two ids sharing a name fail the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1" to "tree", "2" to "tree", "3" to "loc_3"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "gamevals/loc.json", "duplicate name", "1 and 2", "\"tree\"")
    }

    @Test
    fun `names that collide only after escaping fail the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1" to "big-tree", "2" to "big_tree"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "collide after escaping", "1 (\"big-tree\")", "2 (\"big_tree\")", "big_tree")
    }

    @Test
    fun `a name that is another id's placeholder fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("5" to "loc_9", "9" to "loc_9x"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "another id's placeholder", "5 is named \"loc_9\"")
    }

    @Test
    fun `a clientscript name that is another id's placeholder fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "clientscript", mapOf("1" to "cs2_4000", "2" to "cs2_2"))
        val message = failureOf(dir, mapOf("clientscript" to "Cs2"))
        assertMentions(message, "another id's placeholder", "1 is named \"cs2_4000\"")
    }

    @Test
    fun `an empty name fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1" to "", "2" to "loc_2"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "empty or 'null' name", "1 -> \"\"")
    }

    @Test
    fun `a whitespace-only name fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1" to "   ", "2" to "loc_2"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "empty or 'null' name", "1 ->")
    }

    @Test
    fun `the literal name null fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1" to "null", "2" to "loc_2"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "empty or 'null' name", "1 -> \"null\"")
    }

    @Test
    fun `a name that is not a string fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1" to "tree"), rawEntries = "\"1\": 4151")
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "loc 1", "name must be a string")
    }

    @Test
    fun `a non-numeric id fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1a" to "tree"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "malformed id", "\"1a\"")
    }

    @Test
    fun `a malformed component key fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "component", mapOf("679" to "inventory:inv"))
        val message = failureOf(dir, mapOf("component" to "Component"))
        assertMentions(message, "malformed component key", "\"679\"", "<interfaceId>:<componentId>")
    }

    @Test
    fun `a malformed component name fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "component", mapOf("679:0" to "inventory"))
        val message = failureOf(dir, mapOf("component" to "Component"))
        assertMentions(message, "malformed component name", "679:0 -> \"inventory\"", "component_679_0")
    }

    @Test
    fun `an interface named two ways in component json fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "component", mapOf("679:0" to "inventory:inv", "679:1" to "backpack:slot"))
        val message = failureOf(dir, mapOf("component" to "Component"))
        assertMentions(message, "interface named two ways", "interface 679", "\"inventory\"", "\"backpack\"")
    }

    @Test
    fun `component json and interface json disagreeing on a name fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "component", mapOf("679:0" to "inventory:inv"))
        Fixtures.catalog(dir, "interface", mapOf("679" to "backpack"))
        val message = failureOf(dir, mapOf("interface" to "Interface", "component" to "Component"))
        assertMentions(message, "disagree on interface names", "interface 679", "\"backpack\" in interface.json", "\"inventory\" in component.json")
    }

    @Test
    fun `an id missing from the space the coverage block claims fails the build`() {
        val dir = catalogs()
        Fixtures.catalog(
            dir,
            "loc",
            mapOf("0" to "loc_0", "1" to "tree", "3" to "loc_3"),
            coverage = "{ \"cache_ids\": 4, \"named\": 1, \"placeholder\": 2 }",
        )
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "coverage disagrees with the entries", "cache_ids says 4", "3 entries", "ids missing", "2")
    }

    @Test
    fun `coverage counts that disagree with the entries fail the build`() {
        val dir = catalogs()
        Fixtures.catalog(
            dir,
            "loc",
            mapOf("0" to "tree", "1" to "loc_1"),
            coverage = "{ \"cache_ids\": 2, \"named\": 2, \"placeholder\": 0 }",
        )
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "coverage.named says 2 but 1 entries carry a name", "coverage.placeholder says 0 but 1")
    }

    @Test
    fun `a missing catalog fails the build`() {
        val dir = catalogs()
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "catalog for type 'loc' is missing", "loc.json")
    }

    @Test
    fun `a damaged catalog fails the build`() {
        val dir = catalogs()
        File(dir, "loc.json").writeText("{ \"entries\": { \"1\": \"tree\" ")
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "loc.json", "not readable JSON")
    }

    @Test
    fun `every defect in a catalog is reported at once`() {
        val dir = catalogs()
        Fixtures.catalog(dir, "loc", mapOf("1" to "tree", "2" to "tree", "3" to "", "4" to "loc_9"))
        val message = failureOf(dir, mapOf("loc" to "Loc"))
        assertMentions(message, "duplicate name", "empty or 'null' name", "another id's placeholder")
    }

    @Test
    fun `the real catalogs pass validation`() {
        val dir = File("../gamevals")
        if (!dir.isDirectory) return
        for (type in GAMEVAL_CLASSES.keys) validateCatalog(readCatalog(type, File(dir, "$type.json")))
        val component = readCatalog(COMPONENT, File(dir, "component.json"))
        val interfaces = readCatalog("interface", File(dir, "interface.json"))
        validateInterfaceNamesAgree(component, interfaces)
    }
}
