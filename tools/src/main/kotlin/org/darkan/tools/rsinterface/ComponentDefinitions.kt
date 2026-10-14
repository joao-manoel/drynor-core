package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Low level helpers for the *definition* half of the interface editor.
 *
 * [InterfaceDocument] edits `InterfaceComponentDefinition`s in place and then
 * rebuilds an [InterfaceInstance] from them, so everything that has to hold true
 * about a definition regardless of who mutated it lives here: the packed id
 * invariant, deep copying (a plain `copy()` shares every array and hook with the
 * original, which makes undo snapshots useless), and the component-hash
 * detection used when component indices shift.
 *
 * ### The packed id invariant
 *
 * A component's array position is simultaneously
 * - its **file number** inside archive `interfaceId` of cache index 3,
 * - the **low 16 bits of its id** (`componentIndex or (interfaceId shl 16)`),
 * - the value other components store in [InterfaceComponentDefinition.parent],
 * - and the low 16 bits of every CS2 component hash that addresses it.
 *
 * There is no indirection anywhere: inserting or deleting a component silently
 * repoints all four. Every structural edit therefore has to rewrite `id` and
 * `parent` for the whole array, which is what [InterfaceDocument] does.
 */
object ComponentDefinitions {

    /** Parent value meaning "no parent"; the interface itself is the container. */
    const val ROOT = -1

    /**
     * A component index has to fit in the low 16 bits of the packed id, and 65535
     * is reserved by the decoder as the "no parent" marker, so this is the highest
     * addressable component count.
     */
    const val MAX_COMPONENTS = 0xffff

    fun pack(interfaceId: Int, index: Int): Int = (interfaceId shl 16) or index

    fun interfaceOf(hash: Int): Int = hash shr 16

    fun indexOf(hash: Int): Int = hash and 0xffff

    /**
     * A fresh component of [type] belonging to [interfaceId] at [index].
     *
     * The id **must** be set: [world.gregs.voidps.cache.definition.encoder.InterfaceEncoder]
     * returns without writing a single byte when `id == -1`, which is the data
     * class default, so a component built with the plain constructor encodes to an
     * empty cache file and vanishes on the next decode.
     */
    fun create(interfaceId: Int, index: Int, type: Int = ComponentType.CONTAINER): InterfaceComponentDefinition =
        InterfaceComponentDefinition(id = pack(interfaceId, index), type = type)

    /** Copies a hook, including its argument array, or returns null. */
    fun copyHook(hook: InterfaceComponentHook?): InterfaceComponentHook? =
        if (hook == null) null else InterfaceComponentHook(hook.script, hook.args.copyOf())

    /**
     * A fully independent copy of [source].
     *
     * `InterfaceComponentDefinition.copy()` is a shallow copy: the option array,
     * the key binding arrays, the hook objects and the [InterfaceComponentSetting]
     * are shared with the original. Undo snapshots and [InterfaceDocument.duplicateComponent]
     * both need real copies, otherwise editing the copy edits the snapshot.
     */
    @Suppress("LongMethod")
    fun deepCopy(source: InterfaceComponentDefinition): InterfaceComponentDefinition = source.copy(
        keyRepeats = source.keyRepeats?.copyOf(),
        keyCodes = source.keyCodes?.copyOf(),
        keyModifiers = source.keyModifiers?.copyOf(),
        options = source.options?.copyOf(),
        mouseIcon = source.mouseIcon?.copyOf(),
        setting = InterfaceComponentSetting(source.setting.setting, source.setting.anInt7413),
        clientParams = source.clientParams?.let { HashMap(it) },
        onLoadScript = copyHook(source.onLoadScript),
        onMouseOver = copyHook(source.onMouseOver),
        onMouseLeaveScript = copyHook(source.onMouseLeaveScript),
        hookParams = copyHook(source.hookParams),
        onTargetEnter = copyHook(source.onTargetEnter),
        onVarpTransmit = copyHook(source.onVarpTransmit),
        mouseLeaveScript = copyHook(source.mouseLeaveScript),
        onStatTransmit = copyHook(source.onStatTransmit),
        onTimer = copyHook(source.onTimer),
        params = copyHook(source.params),
        onTargetLeave = copyHook(source.onTargetLeave),
        popupScript = copyHook(source.popupScript),
        onClick = copyHook(source.onClick),
        onClickRepeat = copyHook(source.onClickRepeat),
        onRelease = copyHook(source.onRelease),
        onHold = copyHook(source.onHold),
        onDrag = copyHook(source.onDrag),
        onDragComplete = copyHook(source.onDragComplete),
        onMouseMove = copyHook(source.onMouseMove),
        onKey = copyHook(source.onKey),
        onScrollWheel = copyHook(source.onScrollWheel),
        varps = source.varps?.copyOf(),
        mouseLeaveArrayParams = source.mouseLeaveArrayParams?.copyOf(),
        statTransmitFilter = source.statTransmitFilter?.copyOf(),
        keyPressArray = source.keyPressArray?.copyOf(),
        mouseWheelArray = source.mouseWheelArray?.copyOf(),
        extras = source.extras?.toMap()
    )

    /**
     * Whether [value] looks like a packed component hash addressing component
     * `value and 0xffff` of [interfaceId].
     *
     * Hook arguments are untyped - the cache stores each one as either an int or a
     * string with no hint about what the int means - so this is necessarily a
     * heuristic. It is deliberately narrow:
     * - negatives are excluded, which also excludes the client's event
     *   placeholders (everything below -2147483639 is swapped for mouse position,
     *   source component, clicked option or typed key before the script runs),
     * - the high 16 bits must be exactly [interfaceId], so hashes pointing at
     *   *other* interfaces are never touched,
     * - the low 16 bits must address a component that exists.
     */
    fun isComponentHash(value: Int, interfaceId: Int, componentCount: Int): Boolean =
        value >= 0 && interfaceOf(value) == interfaceId && indexOf(value) < componentCount

    /**
     * Rewrites every integer hook argument of [definition] through [remap], which
     * returns the replacement value or null to leave the argument alone.
     *
     * Hooks are reached through [InterfaceComponentDefinition.hooks], which hands
     * back the live hook objects, so mutating `args` in place is enough - there is
     * no need to reassign the 21 hook fields.
     *
     * @return how many arguments changed.
     */
    fun remapHookArgs(definition: InterfaceComponentDefinition, remap: (Int) -> Int?): Int {
        var changed = 0
        for (hook in definition.hooks().values) {
            val args = hook.args
            for (i in args.indices) {
                val value = args[i] as? Int ?: continue
                val mapped = remap(value) ?: continue
                if (mapped != value) {
                    args[i] = mapped
                    changed++
                }
            }
        }
        return changed
    }

    /** Every integer hook argument of [definition] that looks like a component hash. */
    fun componentHashArgs(definition: InterfaceComponentDefinition): List<Int> {
        val hashes = ArrayList<Int>()
        for (hook in definition.hooks().values) {
            for (arg in hook.args) {
                if (arg is Int && arg >= 0 && interfaceOf(arg) != 0) {
                    hashes.add(arg)
                }
            }
        }
        return hashes
    }
}

/**
 * Reflective access to every declared field of `InterfaceComponentDefinition`.
 *
 * The JSON sidecar and the encode/decode round-trip check both have to cover
 * *all* ~100 fields of a component, and both have to keep covering them when the
 * cache library gains a field. Hand written field lists drift; this does not.
 *
 * Only field types that actually occur on the definition are supported, and
 * anything unexpected raises [IllegalArgumentException] rather than being
 * silently skipped - a new field type must be handled deliberately.
 */
object ComponentFields {

    /** Declared instance fields in declaration order. */
    val fields: List<Field> = InterfaceComponentDefinition::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .onEach { it.isAccessible = true }

    private val byName: Map<String, Field> = fields.associateBy { it.name }

    /**
     * Fields that no `.dat` byte ever carries.
     *
     * - `clientParams` is never read by the decoder and never written by the
     *   encoder, and is a `val` so it cannot be assigned after construction.
     * - `stringId` and `extras` are server side annotations from the voidps
     *   definition mixins, not part of the if3 format.
     */
    val transientFields: Set<String> = setOf("clientParams", "stringId", "extras")

    /**
     * Fields the decoder *derives* from other fields rather than reading directly.
     * A difference in one of these after a round-trip is expected rather than a
     * loss of data.
     */
    val derivedFields: Set<String> = setOf("id", "hasScript", "clickable")

    fun field(name: String): Field? = byName[name]

    fun get(definition: InterfaceComponentDefinition, field: Field): Any? = field.get(definition)

    fun set(definition: InterfaceComponentDefinition, field: Field, value: Any?) {
        field.set(definition, value)
    }

    /** Array aware equality, so `IntArray` and `Array<String>` compare by content. */
    fun same(a: Any?, b: Any?): Boolean = when {
        a is IntArray && b is IntArray -> a.contentEquals(b)
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is Array<*> && b is Array<*> -> a.contentDeepEquals(b)
        else -> a == b
    }

    /** A short human readable rendering used in difference reports. */
    fun describe(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { describe(it) }
        is InterfaceComponentHook -> "script=${value.script} args=${describe(value.args)}"
        else -> value.toString()
    }
}
