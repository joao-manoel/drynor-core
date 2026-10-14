package org.darkan.core.gameval

import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.constantpool.IntegerEntry
import java.lang.reflect.AccessFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generated constants (`org.darkan.core.gameval.gen.*`, from `:core:generateGamevalConstants`)
 * must agree with the runtime catalog they were generated from, and must now cover it completely:
 * every id of every type has a constant, an unnamed one under its placeholder.
 *
 * The constants are read from the generated class files so the test still runs (and says what is
 * missing) when a catalog or a class is not there.
 */
class GamevalConstantsTest {

    private val genPackage = "org.darkan.core.gameval.gen"

    private val regenerate = "regenerate with ./gradlew :core:generateGamevalConstants"

    /** Catalog type -> generated class, for every type except the nested `component` layout. */
    private val classes = mapOf(
        Gameval.OBJ to "Obj", Gameval.NPC to "Npc", Gameval.LOC to "Loc", Gameval.SEQ to "Seq",
        Gameval.SPOTANIM to "Spotanim", Gameval.ENUM to "Enum", Gameval.STRUCT to "Struct",
        Gameval.PARAM to "Param", Gameval.INV to "Inv", Gameval.INTERFACE to "Interface",
        Gameval.CLIENTSCRIPT to "Cs2", Gameval.VARP to "Varp", Gameval.VARBIT to "Varbit",
        Gameval.VARC to "Varc", Gameval.VARCSTR to "Varcstr", Gameval.VARCLAN to "Varclan",
        Gameval.VARBITCLAN to "Varbitclan", Gameval.VARCLANSETTING to "Varclansetting",
        Gameval.VARBITCLANSETTING to "Varbitclansetting", Gameval.BAS to "Bas",
        Gameval.HITMARK to "Hitmark", Gameval.HEADBAR to "Headbar", Gameval.CURSOR to "Cursor",
        Gameval.MAPELEMENT to "Mapelement", Gameval.QUEST to "Quest", Gameval.MODEL to "Model",
        Gameval.GRAPHIC to "Graphic", Gameval.SOUND to "Sound", Gameval.MIDI to "Midi",
        Gameval.MATERIAL to "Material", Gameval.FONTMETRICS to "Fontmetrics",
        Gameval.IDK to "Idk", Gameval.OVERLAY to "Overlay", Gameval.UNDERLAY to "Underlay",
        Gameval.MAPSCENE to "Mapscene", Gameval.SKYBOX to "Skybox",
        Gameval.QUICKCHATOPTION to "Quickchatoption", Gameval.QUICKCHATPHRASE to "Quickchatphrase",
        Gameval.WORLDMAP to "Worldmap",
    )

    private fun generatedClass(cls: String): Class<*>? = try {
        Class.forName("$genPackage.$cls")
    } catch (_: ClassNotFoundException) {
        null
    }

    private val constantsByClass = HashMap<String, Map<String, Int>?>()

    /** `<Class>.<field>` walked up the generated superclass chain, or `null` when either is absent. */
    private fun constant(cls: String, field: String): Int? = constants(cls)?.get(field)

    /**
     * Every static int constant reachable through the class, superclass chain included, read from
     * the class files' ConstantValue attributes. Reflection is out: resolving a field makes the JVM
     * scan the class's field table, which over 290,000 constants in shards of 65,000 took 40 s.
     */
    private fun constants(cls: String): Map<String, Int>? {
        val name = "$genPackage.$cls"
        if (name !in constantsByClass) constantsByClass[name] = readConstants(name)
        return constantsByClass[name]
    }

    private fun readConstants(name: String): Map<String, Int>? {
        var current = classFile(name) ?: return null
        val out = LinkedHashMap<String, Int>()
        while (true) {
            for (field in current.fields()) {
                if (!field.flags().has(AccessFlag.STATIC)) continue
                val value = field.findAttribute(Attributes.constantValue()).orElse(null)?.constant() as? IntegerEntry ?: continue
                out.putIfAbsent(field.fieldName().stringValue(), value.intValue())
            }
            val parent = current.superclass().orElse(null)?.asInternalName()?.replace('/', '.')
                ?.takeIf { it.startsWith(genPackage) } ?: break
            current = classFile(parent) ?: break
        }
        return out
    }

    private fun classFile(name: String): ClassModel? {
        val bytes = javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() } ?: return null
        return ClassFile.of().parse(bytes)
    }

    @Test
    fun `Obj coins matches the catalog`() {
        if (!Gameval.has(Gameval.OBJ) || Gameval.id(Gameval.OBJ, "coins") == null) {
            println("skipped: no obj.json catalog with 'coins'")
            return
        }
        val generated = constant("Obj", "coins")
        assertNotNull(generated, "obj.json exists but Obj.coins was not generated; $regenerate")
        assertEquals(Gameval.requireId(Gameval.OBJ, "coins"), generated)
    }

    @Test
    fun `Cs2 quicksort matches the catalog`() {
        val id = Gameval.id(Gameval.CLIENTSCRIPT, "[proc,quicksort]")
        if (id == null) {
            println("skipped: no clientscript.json catalog with [proc,quicksort]")
            return
        }
        // Bare name unless the catalog also carries a [clientscript,quicksort], then category-prefixed.
        val generated = constant("Cs2", "quicksort") ?: constant("Cs2", "proc_quicksort")
        assertNotNull(generated, "clientscript.json exists but Cs2.quicksort was not generated; $regenerate")
        assertEquals(id, generated)
    }

    @Test
    fun `every constant is an id of its catalog and every id has a constant`() {
        var checked = 0
        var total = 0
        for ((type, cls) in classes) {
            if (!Gameval.has(type)) continue
            val generated = constants(cls)
            assertNotNull(generated, "$type.json exists but no $cls class was generated; $regenerate")
            val all = Gameval.allEntries(type)
            val unknown = generated.filterValues { it !in all }
            assertTrue(
                unknown.isEmpty(),
                "$cls has ${unknown.size} constant(s) whose id is not in $type.json (e.g. ${unknown.entries.take(3)}); $regenerate",
            )
            assertEquals(all.size, generated.size, "$cls has ${generated.size} constants for ${all.size} ids in $type.json; $regenerate")
            checked++
            total += generated.size
        }
        println("checked $checked generated class(es), $total constants, against their catalogs")
    }

    @Test
    fun `an id with no proven name is reachable under its placeholder`() {
        var checked = 0
        for ((type, cls) in classes) {
            if (!Gameval.has(type)) continue
            val unnamed = Gameval.allEntries(type).entries.firstOrNull { Gameval.isPlaceholder(type, it.key) } ?: continue
            val placeholder = GamevalCatalog.placeholder(type, unnamed.key.toString())
            assertEquals(
                unnamed.key,
                constant(cls, placeholder),
                "$type ${unnamed.key} has no proven name, so $cls.$placeholder must be its constant; $regenerate",
            )
            checked++
        }
        assertTrue(checked > 0, "no catalog had an unnamed id to check")
        println("checked $checked placeholder constant(s)")
    }

    @Test
    fun `Component has a class per interface and packs every component id`() {
        if (!Gameval.has(GamevalCatalog.COMPONENT)) {
            println("skipped: no component.json catalog")
            return
        }
        val outer = generatedClass("Component")
        assertNotNull(outer, "component.json exists but no Component class was generated; $regenerate")
        // Keyed by the interface id the class itself declares rather than by its name, so this check
        // stays independent of the generator's identifier-escaping rules.
        val nested = outer.declaredClasses.associateBy { it.getDeclaredField("IFACE").also { f -> f.isAccessible = true }.getInt(null) }
        val raw = Gameval.rawEntries(GamevalCatalog.COMPONENT)
        val byIface = raw.keys.groupBy { it.substringBefore(':').toInt() }
        assertEquals(byIface.size, nested.size, "Component has ${nested.size} nested classes for ${byIface.size} interfaces; $regenerate")

        var packed = 0
        for ((iface, keys) in byIface) {
            val cls = nested[iface]
            assertNotNull(cls, "no Component class declares IFACE = $iface; $regenerate")
            val fields = cls.declaredFields.filter { it.type == Int::class.javaPrimitiveType }
            // IFACE and a component can share a value (interface 0, component 0 both pack to 0), so
            // count the fields and match on the set of values.
            val values = fields.map { it.also { f -> f.isAccessible = true }.getInt(null) }.toHashSet()
            assertEquals(keys.size + 1, fields.size, "Component class for interface $iface has ${fields.size} constants for ${keys.size} components plus IFACE; $regenerate")
            for (key in keys) {
                val expected = GamevalCatalog.pack(iface, key.substringAfter(':').toInt())
                assertTrue(expected in values, "component $key is not packed as $expected in the class for interface $iface; $regenerate")
                packed++
            }
        }

        // And one entry whose two halves need no escaping, to pin the name -> constant mapping.
        val plain = Regex("[a-z][a-z0-9_]*")
        val sample = raw.entries.firstOrNull { (_, name) ->
            name.count { it == ':' } == 1 && name.split(":").all { plain.matches(it) && it !in setOf("class", "val", "int") }
        }
        assertNotNull(sample, "no component name was a plain identifier to sample")
        val iface = sample.key.substringBefore(':').toInt()
        val field = nested.getValue(iface).getDeclaredField(sample.value.substringAfter(':'))
        field.isAccessible = true
        assertEquals(GamevalCatalog.pack(iface, sample.key.substringAfter(':').toInt()), field.getInt(null))
        println("checked $packed component constant(s) over ${nested.size} interface classes, sample ${sample.value}")
    }
}
