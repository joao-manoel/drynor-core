package org.darkan.tools.rsinterface

import org.darkan.cs2.Cs2Op
import org.darkan.cs2.ir.VarSpace
import org.darkan.cs2.vm.Cs2Hook
import org.darkan.cs2.vm.Cs2Host
import org.darkan.cs2.vm.Cs2Values
import org.darkan.tools.engine.render.ObjIconStack
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition

/**
 * Answers CS2 opcodes against a live interface.
 *
 * Only the opcodes that actually move an interface are implemented; the virtual
 * machine pads anything else to the right shape, so an unimplemented opcode
 * behaves as a no-op returning zero rather than derailing the script.
 *
 * `CC_*` opcodes act on an "active component" rather than naming one. The
 * client keeps two such registers and picks between them with the instruction's
 * own operand, which is why [activeComponents] has two slots.
 */
class InterfaceCs2Host(
    private val interfaces: (Int) -> InterfaceInstance?,
    private val enums: (Int) -> EnumDefinition?,
    private val structs: (Int) -> StructDefinition?,
    private val params: (Int) -> ParamDefinition?,
) : Cs2Host {

    private val varps = HashMap<Int, Int>()
    private val varbits = HashMap<Int, Int>()
    private val varcs = HashMap<Int, Int>()
    private val varcStrings = HashMap<Int, String>()

    /** The two `CC_*` target registers. */
    private val activeComponents = arrayOfNulls<ComponentState>(2)

    /**
     * Hooks the running scripts have bound, keyed by component hash then by the
     * event suffix of the opcode that bound them - `IF_SETONCLICK` stores under
     * `CLICK`, `CC_SETONMOUSEOVER` under `MOUSEOVER`.
     *
     * The suffix is not the hook's meaning. Use [boundHook] to look one up by the
     * definition field it corresponds to; [HOOK_SUFFIX_BY_FIELD] documents the
     * pairing and where it came from.
     */
    val boundHooks: MutableMap<Int, MutableMap<String, Cs2Hook>> = LinkedHashMap()

    /** Messages a script produced, shown in the editor. */
    val log: MutableList<String> = ArrayList()

    var onChanged: (() -> Unit)? = null

    /**
     * Notified after a script writes a varp or varbit, with the varp id.
     *
     * `IF_SETONVARTRANSMIT` hooks fire off varp writes, so something has to turn a
     * write into a dispatch. The host is the only place that sees every write, but
     * it knows nothing about components, so it hands the id out and lets the
     * interaction runtime decide who cares.
     */
    var onVarpWritten: ((Int) -> Unit)? = null

    /** Notified after a script writes a client int var, with the varc id. */
    var onVarcWritten: ((Int) -> Unit)? = null

    /** Notified after a script writes a client string var, with the varc id. */
    var onVarcStringWritten: ((Int) -> Unit)? = null

    /**
     * Asked to start a drag of a component from a grab offset inside it, for
     * `IF_DRAGPICKUP` / `CC_DRAGPICKUP`.
     *
     * The host knows nothing about pointer state, so the interaction runtime supplies
     * this; when nothing is listening the opcode is a no-op, exactly as an
     * unimplemented one would be.
     */
    var onDragPickup: ((ComponentState, Int, Int) -> Unit)? = null

    // --- Live input state, answered by the query opcodes -------------------

    /** Pointer position in the interface's own coordinates; `GET_MOUSEX`/`GET_MOUSEY`. */
    var mouseX: Int = -1
    var mouseY: Int = -1

    /** Mouse button state answered by `GET_MOUSEBUTTONS`. */
    var mouseLeft: Boolean = false
    var mouseRight: Boolean = false
    var mouseMiddle: Boolean = false

    /** Client key codes currently held, answered by the `KEYHELD_*` opcodes. */
    val heldKeys: MutableSet<Int> = LinkedHashSet()

    // `PARAHEIGHT`. Scripts size a row from it, so the 0 an unimplemented opcode answers leaves
    // every wrapped label present but zero pixels tall.
    var paragraphLines: ((String, Int, Int) -> Int)? = null

    fun setActive(slot: Int, component: ComponentState?) {
        activeComponents[slot.coerceIn(0, 1)] = component
    }

    fun clearHooks() {
        boundHooks.clear()
    }

    /**
     * The hook a script bound for the definition field [field] on [hash], or null.
     *
     * Callers should prefer this over indexing [boundHooks] directly: it takes the
     * same key `InterfaceComponentDefinition.hooks()` uses, so a runtime-bound
     * hook and a cache-defined one are looked up by the same name.
     */
    fun boundHook(hash: Int, field: String): Cs2Hook? {
        val suffix = HOOK_SUFFIX_BY_FIELD[field] ?: return null
        return boundHooks[hash]?.get(suffix)
    }

    override fun invoke(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values {
        // The runtime-component opcodes name their target by hash *and* move the
        // active register, so neither half of the split below fits them. They are
        // resolved first and the rest of this method never sees them.
        runtimeComponentOp(op, operand, args)?.let { return it }

        val activeTarget = op.name.startsWith("CC_")
        val component = when {
            activeTarget -> activeComponents[if (operand == 1) 1 else 0]
            args.ints.isNotEmpty() -> componentOf(args.ints.last())
            else -> null
        }

        // Setters take the component id last, so the value arguments come first.
        val values = if (activeTarget) args.ints else args.ints.dropLast(1)

        return when (op) {
            Cs2Op.IF_SETHIDE, Cs2Op.CC_SETHIDE -> mutate(component) { it.hidden = values.first() != 0 }
            Cs2Op.IF_SETCOLOUR, Cs2Op.CC_SETCOLOUR -> mutate(component) { it.colour = values.first() }
            Cs2Op.IF_SETTRANS, Cs2Op.CC_SETTRANS -> mutate(component) { it.transparency = values.first() }
            Cs2Op.IF_SETGRAPHIC, Cs2Op.CC_SETGRAPHIC -> mutate(component) { it.graphicId = values.first() }
            Cs2Op.IF_SETTEXT, Cs2Op.CC_SETTEXT -> mutate(component) { it.text = args.strings.firstOrNull() ?: "" }
            Cs2Op.IF_SETFILL, Cs2Op.CC_SETFILL ->
                mutate(component) { it.filled = values.first() != 0 }
            // These three re-resolve the component's box straight away; see
            // [InterfaceInstance.resolve] for why waiting for the next frame breaks
            // every script that builds a list.
            Cs2Op.IF_SETSIZE, Cs2Op.CC_SETSIZE ->
                reshape(component) { it.setSize(values[0], values[1], values[2], values[3]) }
            Cs2Op.IF_SETPOSITION, Cs2Op.CC_SETPOSITION ->
                reshape(component) { it.setPosition(values[0], values[1], values[2], values[3]) }
            Cs2Op.IF_SETASPECT, Cs2Op.CC_SETASPECT -> reshape(component) { it.setAspect(values[0], values[1]) }
            Cs2Op.IF_SETSCROLLSIZE, Cs2Op.CC_SETSCROLLSIZE ->
                mutate(component) { it.setScrollSize(values[0], values[1]) }
            Cs2Op.IF_SETSCROLLPOS, Cs2Op.CC_SETSCROLLPOS ->
                mutate(component) { it.setScrollPos(values[0], values[1]) }
            Cs2Op.IF_SETOBJECT, Cs2Op.CC_SETOBJECT, Cs2Op.IF_SETOBJECT_WEARCOL, Cs2Op.CC_SETOBJECT_WEARCOL ->
                mutate(component) { it.setObject(values[0], values[1]) }
            Cs2Op.IF_SETOBJECT_NONUM, Cs2Op.CC_SETOBJECT_NONUM,
            Cs2Op.IF_SETOBJECT_WEARCOL_NONUM, Cs2Op.CC_SETOBJECT_WEARCOL_NONUM ->
                mutate(component) { it.setObject(values[0], values[1], ObjIconStack.NONE) }
            Cs2Op.IF_SETOBJECT_ALWAYSNUM, Cs2Op.CC_SETOBJECT_ALWAYSNUM,
            Cs2Op.IF_SETOBJECT_WEARCOL_ALWAYSNUM, Cs2Op.CC_SETOBJECT_WEARCOL_ALWAYSNUM ->
                mutate(component) { it.setObject(values[0], values[1], ObjIconStack.ALWAYS) }
            Cs2Op.IF_SETMODEL, Cs2Op.CC_SETMODEL -> mutate(component) { it.setModel(values[0]) }
            Cs2Op.IF_SETMODELANGLE, Cs2Op.CC_SETMODELANGLE ->
                mutate(component) { it.setModelAngle(values[0], values[1], values[2], values[3]) }
            Cs2Op.IF_SETTEXTALIGN, Cs2Op.CC_SETTEXTALIGN ->
                mutate(component) { it.setTextAlign(values[0], values[1], values[2]) }
            Cs2Op.IF_SETTEXTFONT, Cs2Op.CC_SETTEXTFONT -> mutate(component) { it.fontId = values.first() }
            Cs2Op.IF_SETTEXTSHADOW, Cs2Op.CC_SETTEXTSHADOW -> mutate(component) { it.shadow = values.first() == 1 }
            Cs2Op.IF_SETMAXLINES, Cs2Op.CC_SETMAXLINES -> mutate(component) { it.maxTextLines = values.first() }

            Cs2Op.IF_SETHFLIP, Cs2Op.CC_SETHFLIP -> mutate(component) { it.flipHorizontal = values.first() == 1 }
            Cs2Op.IF_SETVFLIP, Cs2Op.CC_SETVFLIP -> mutate(component) { it.flipVertical = values.first() == 1 }
            Cs2Op.IF_SETTILING, Cs2Op.CC_SETTILING -> mutate(component) { it.tiling = values.first() == 1 }
            Cs2Op.IF_SETOUTLINE, Cs2Op.CC_SETOUTLINE -> mutate(component) { it.borderThickness = values.first() }
            Cs2Op.IF_SETGRAPHICSHADOW, Cs2Op.CC_SETGRAPHICSHADOW ->
                mutate(component) { it.graphicShadow = values.first() }
            Cs2Op.IF_SETLINEWID, Cs2Op.CC_SETLINEWID -> mutate(component) { it.lineWidth = values.first() }

            Cs2Op.IF_SETDRAGRENDERBEHAVIOUR, Cs2Op.CC_SETDRAGRENDERBEHAVIOUR ->
                mutate(component) { it.dragType = values.first() }

            // Opcode 68/899. Jagex's name is `SETDRAGGABLE`; the client calls it
            // `SETDEPTHCHILD` and it assigns
            // `Component.depthChildOverride` from a (component hash, slot) pair. It has
            // nothing to do with the drag dead zone or dead time - those are the two
            // opcodes below - and reading it that way is what left every scrollbar
            // thumb undraggable: the override is the only thing that makes a
            // runtime-created component pass the client's `hasDepthFlags`.
            Cs2Op.IF_SETDRAGGABLE, Cs2Op.CC_SETDRAGGABLE -> mutate(component) {
                it.depthChild = depthChildOf(values[0], values[1])
            }

            // Options are addressed 1-based by every opcode that touches them.
            Cs2Op.IF_SETOP, Cs2Op.CC_SETOP ->
                mutate(component) { it.setOption(values.first() - 1, args.strings.firstOrNull() ?: "") }
            Cs2Op.IF_CLEAROPS, Cs2Op.CC_CLEAROPS -> mutate(component) { it.clearOptions() }
            Cs2Op.IF_SETOPBASE, Cs2Op.CC_SETOPBASE ->
                mutate(component) { it.opBase = args.strings.firstOrNull() ?: "" }
            Cs2Op.IF_SETOPCURSOR, Cs2Op.CC_SETOPCURSOR ->
                mutate(component) { it.setOptionCursor(values[0] - 1, values[1]) }
            Cs2Op.IF_SETMOUSEOVERCURSOR, Cs2Op.CC_SETMOUSEOVERCURSOR ->
                mutate(component) { it.hoverCursor = values.first() }
            Cs2Op.IF_SETTARGETOPCURSOR, Cs2Op.CC_SETTARGETOPCURSOR ->
                mutate(component) { it.targetCursor = values.first() }
            Cs2Op.IF_SETOPCHAR, Cs2Op.CC_SETOPCHAR ->
                mutate(component) { it.setOptionKeyChar(values[0] - 1, values[1]) }
            Cs2Op.IF_SETOPTCHAR, Cs2Op.CC_SETOPTCHAR ->
                mutate(component) { it.setOptionKeyChar(ComponentState.OPTION_COUNT, values.first()) }

            Cs2Op.IF_SETNOCLICKTHROUGH, Cs2Op.CC_SETNOCLICKTHROUGH ->
                mutate(component) { it.noClickThrough = values.first() == 1 }
            Cs2Op.IF_SETDRAGDEADZONE, Cs2Op.CC_SETDRAGDEADZONE ->
                mutate(component) { it.dragDeadzone = values.first() }
            Cs2Op.IF_SETDRAGDEADTIME, Cs2Op.CC_SETDRAGDEADTIME ->
                mutate(component) { it.dragDeadTime = values.first() }

            // `CC_DRAGPICKUP` starts a drag of the active component from a grab offset
            // the script chooses, rather than from wherever the pointer happens to be.
            // A scrollbar's track uses it to hand the drag to the thumb, which is how
            // clicking the gutter pages the list.
            Cs2Op.IF_DRAGPICKUP, Cs2Op.CC_DRAGPICKUP -> {
                if (component != null) {
                    onDragPickup?.invoke(component, values.getOrElse(0) { 0 }, values.getOrElse(1) { 0 })
                }
                Cs2Values.EMPTY
            }

            Cs2Op.IF_CLEARSCRIPTHOOKS, Cs2Op.CC_CLEARSCRIPTHOOKS -> {
                component?.let {
                    boundHooks.remove(it.hash)
                    it.scripted = false
                    it.optionHook = it.definition.params != null
                }
                Cs2Values.EMPTY
            }

            Cs2Op.IF_GETWIDTH, Cs2Op.CC_GETWIDTH -> ints(component?.width ?: 0)
            Cs2Op.IF_GETHEIGHT, Cs2Op.CC_GETHEIGHT -> ints(component?.height ?: 0)
            // The client returns `Component.x`/`.y`, which is the position the layout
            // pass computed *relative to the parent's content box* - not a screen
            // coordinate. Scripts feed the value straight back to `IF_SETPOSITION`
            // with position mode 0, so answering with [ComponentState.screenX] moves
            // the component by the whole interface's origin every time one asks.
            Cs2Op.IF_GETX, Cs2Op.CC_GETX -> ints(component?.x ?: 0)
            Cs2Op.IF_GETY, Cs2Op.CC_GETY -> ints(component?.y ?: 0)
            Cs2Op.IF_GETHIDE, Cs2Op.CC_GETHIDE -> ints(if (component?.hidden == true) 1 else 0)
            Cs2Op.IF_GETTRANS, Cs2Op.CC_GETTRANS -> ints(component?.transparency ?: 0)
            Cs2Op.IF_GETCOLOUR, Cs2Op.CC_GETCOLOUR -> ints(component?.colour ?: 0)
            Cs2Op.IF_GETSCROLLX, Cs2Op.CC_GETSCROLLX -> ints(component?.scrollX ?: 0)
            Cs2Op.IF_GETSCROLLY, Cs2Op.CC_GETSCROLLY -> ints(component?.scrollY ?: 0)
            Cs2Op.IF_GETSCROLLWIDTH, Cs2Op.CC_GETSCROLLWIDTH -> ints(component?.scrollWidth ?: 0)
            Cs2Op.IF_GETSCROLLHEIGHT, Cs2Op.CC_GETSCROLLHEIGHT -> ints(component?.scrollHeight ?: 0)
            Cs2Op.IF_GETGRAPHIC, Cs2Op.CC_GETGRAPHIC -> ints(component?.graphicId ?: -1)
            Cs2Op.IF_GETTEXT, Cs2Op.CC_GETTEXT -> strings(component?.text ?: "")
            Cs2Op.IF_GETOP, Cs2Op.CC_GETOP -> strings(component?.options?.getOrNull(values.first() - 1) ?: "")
            Cs2Op.IF_GETOPBASE, Cs2Op.CC_GETOPBASE -> strings(component?.opBase ?: "")
            Cs2Op.IF_GETTARGETMASK, Cs2Op.CC_GETTARGETMASK -> ints(component?.useOptionFlags ?: 0)

            Cs2Op.GET_MOUSEX -> ints(mouseX)
            Cs2Op.GET_MOUSEY -> ints(mouseY)
            Cs2Op.GET_MOUSEBUTTONS -> ints(
                if (mouseLeft) 1 else 0,
                if (mouseRight) 1 else 0,
                if (mouseMiddle) 1 else 0,
            )

            Cs2Op.KEYHELD_ALT -> ints(if (heldKeys.contains(KEY_ALT)) 1 else 0)
            Cs2Op.KEYHELD_CTRL -> ints(if (heldKeys.contains(KEY_CTRL)) 1 else 0)
            Cs2Op.KEYHELD_SHIFT -> ints(if (heldKeys.contains(KEY_SHIFT)) 1 else 0)

            Cs2Op.STAT, Cs2Op.STAT_BASE -> ints(stat(args.ints.firstOrNull() ?: -1))

            Cs2Op.PARAHEIGHT -> ints(
                paragraphLines?.invoke(
                    args.strings.firstOrNull() ?: "",
                    args.ints.getOrElse(0) { 0 },
                    args.ints.getOrElse(1) { -1 },
                ) ?: 1,
            )

            Cs2Op.ENUM -> enumLookup(args.ints)
            Cs2Op.ENUM_STRING -> strings(enums(args.ints.firstOrNull() ?: -1)?.getString(args.ints.getOrElse(1) { 0 }) ?: "")
            Cs2Op.ENUM_GETOUTPUTCOUNT -> ints(enums(args.ints.firstOrNull() ?: -1)?.length ?: 0)
            Cs2Op.STRUCT_PARAM -> structParam(args.ints)

            Cs2Op.MES, Cs2Op.WRITECONSOLE -> {
                args.strings.forEach { log.add(it) }
                Cs2Values.EMPTY
            }

            else -> Cs2Host.defaults(op)
        }
    }

    /**
     * The opcodes that create, destroy and re-target runtime components, or null
     * when [op] is not one of them.
     *
     * A great many RS widgets are not in the cache at all: the cache holds an empty
     * layer and a load script fills it with `CC_CREATE` children - the nine graphics
     * of a dropdown's border, the six parts of a scrollbar, one text child per
     * dropdown row. Without these opcodes the layer stays empty and every `CC_*`
     * call after the create lands on the layer itself, which is why an
     * unimplemented `CC_CREATE` shows up as a widget that is not merely unstyled
     * but absent.
     *
     * All of them address a container by packed hash and most also move the active
     * `CC_*` register, so they cannot go through the hash-or-register split in
     * [invoke]. The register is chosen by the instruction's operand, exactly as the
     * client picks between its two `CS2Interface` slots.
     */
    private fun runtimeComponentOp(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values? {
        val slot = if (operand == 1) 1 else 0
        return when (op) {
            Cs2Op.CC_CREATE -> {
                val container = args.ints.getOrElse(0) { -1 }
                val child = instanceOf(container)?.let { instance ->
                    val parent = instance.stateByHash(container) ?: return@let null
                    instance.createChild(parent.index, args.ints.getOrElse(1) { 0 }, args.ints.getOrElse(2) { 0 })
                }
                if (child == null) {
                    log.add("cc_create failed on ${args.ints}")
                } else {
                    // The synthetic index may have been recycled from a deleted
                    // component, and a brand new component has no callbacks.
                    boundHooks.remove(child.hash)
                    activeComponents[slot] = child
                    onChanged?.invoke()
                }
                Cs2Values.EMPTY
            }

            // Despite the `CC_` prefix this one names its container by hash rather
            // than using the active register, as the client's `clearCompChildren` does.
            Cs2Op.CC_DELETEALL -> {
                val container = args.ints.getOrElse(0) { -1 }
                instanceOf(container)?.let { instance ->
                    instance.stateByHash(container)?.let { instance.deleteChildren(it.index) }
                }
                onChanged?.invoke()
                Cs2Values.EMPTY
            }

            Cs2Op.CC_DELETE -> {
                activeComponents[slot]?.let { target ->
                    instanceOf(target.hash)?.deleteChild(target)
                    activeComponents[slot] = null
                    onChanged?.invoke()
                }
                Cs2Values.EMPTY
            }

            // The opcode table calls this `IF_GETNEXTSUBID`; the client calls it
            // `IF_GETSUBCOUNT` and counts the filled slots. Both descriptions agree,
            // because the count is also the index of the next free slot, which is
            // exactly what every caller passes straight to `CC_CREATE`.
            Cs2Op.IF_GETNEXTSUBID -> {
                val container = args.ints.getOrElse(0) { -1 }
                val count = instanceOf(container)?.let { instance ->
                    instance.stateByHash(container)?.let { instance.slotCount(it.index) }
                }
                ints(count ?: 0)
            }

            // `IF_FIND` / `CC_FIND` are the client's `CC_SETCHILD` / `CC_SETCHILD_SLOT`:
            // they point the active register at a component so the `CC_*` setters can
            // work on something other than the last thing created.
            Cs2Op.IF_FIND -> {
                val found = componentOf(args.ints.getOrElse(0) { -1 })
                activeComponents[slot] = found
                ints(if (found == null) 0 else 1)
            }

            Cs2Op.CC_FIND -> {
                val container = args.ints.getOrElse(0) { -1 }
                val childSlot = args.ints.getOrElse(1) { -1 }
                val found = instanceOf(container)?.let { instance ->
                    val parent = instance.stateByHash(container) ?: return@let null
                    if (childSlot == -1) parent else instance.slotChild(parent.index, childSlot)
                }
                activeComponents[slot] = found
                ints(if (found == null) 0 else 1)
            }

            // The client returns `Component.parentId`, which is the parent's packed
            // hash and -1 at the root of the interface.
            Cs2Op.IF_GETLAYER -> {
                val hash = args.ints.getOrElse(0) { -1 }
                val parent = instanceOf(hash)?.let { instance ->
                    instance.stateByHash(hash)?.let { instance.parentOf(it) }
                }
                ints(parent?.hash ?: -1)
            }

            // Not the same question as `IF_GETLAYER`: this one crosses out of the
            // component's own interface into whatever container mounts it, which is
            // how a script walks all the way up to the game frame to test whether a
            // component is really on screen. It has to bottom out at -1, because a
            // script that walks with it loops forever otherwise.
            Cs2Op.IF_GETPARENTLAYER -> {
                val hash = args.ints.getOrElse(0) { -1 }
                val parent = instanceOf(hash)?.let { instance ->
                    instance.stateByHash(hash)?.let { instance.enclosing(it) }
                }
                ints(parent?.hash ?: -1)
            }

            else -> null
        }
    }

    private fun componentOf(hash: Int): ComponentState? =
        interfaces(hash shr 16)?.stateByHash(hash)

    /**
     * The interface owning [hash], resolved the same way [componentOf] does: by the
     * hash's high half. A component reached only through a mounted sub-interface is
     * not found, matching the existing lookup rather than diverging from it.
     */
    private fun instanceOf(hash: Int): InterfaceInstance? = interfaces(hash shr 16)

    /**
     * The component a (hash, slot) pair addresses, as the client's
     * `Component.listIcomponent` resolves it.
     */
    private fun depthChildOf(hash: Int, slot: Int): ComponentState? =
        instanceOf(hash)?.slotChildByHash(hash, slot)

    private fun mutate(component: ComponentState?, change: (ComponentState) -> Unit): Cs2Values {
        if (component != null) {
            change(component)
            onChanged?.invoke()
        }
        return Cs2Values.EMPTY
    }

    /**
     * [mutate] for the opcodes that move the box model, which additionally resolve
     * the component's new size and position immediately. The very next instruction
     * is routinely `CC_GETHEIGHT` on the same component, and it has to see the size
     * the script just set rather than the one from before it.
     */
    private fun reshape(component: ComponentState?, change: (ComponentState) -> Unit): Cs2Values {
        if (component != null) {
            change(component)
            instanceOf(component.hash)?.resolve(component)
            onChanged?.invoke()
        }
        return Cs2Values.EMPTY
    }

    private fun ints(vararg values: Int) = Cs2Values(ints = values.toList())

    private fun strings(vararg values: String) = Cs2Values(strings = values.toList())

    /** `ENUM` takes key type, value type, enum id and key. */
    private fun enumLookup(args: List<Int>): Cs2Values {
        if (args.size < 4) return Cs2Values.EMPTY
        val definition = enums(args[2]) ?: return if (args[1] == 's'.code) strings("") else ints(-1)
        val key = args[3]
        return if (args[1] == 's'.code) strings(definition.getString(key) ?: "") else ints(definition.getInt(key))
    }

    /** `STRUCT_PARAM` takes a struct id and a param id. */
    private fun structParam(args: List<Int>): Cs2Values {
        if (args.size < 2) return Cs2Values.EMPTY
        val param = params(args[1])
        val struct = structs(args[0])
        return if (param?.isString() == true) {
            strings(struct?.getStringValue(args[1]) ?: param.defaultString ?: "")
        } else {
            ints(struct?.getIntValue(args[1], param?.defaultInt ?: 0) ?: (param?.defaultInt ?: 0))
        }
    }

    override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) {
        val activeTarget = op.name.startsWith("CC_")
        val state = if (activeTarget) activeComponents[0] else componentOf(component)
        val target = if (activeTarget) state?.hash ?: return else component
        val event = eventSuffix(op.name)
        val events = boundHooks.getOrPut(target) { LinkedHashMap() }
        if (hook == null) events.remove(event) else events[event] = hook
        // Mark the component itself so the interaction runtime's interactive test
        // stays a field read. A component built by CC_CREATE has a blank definition,
        // so this flag is the only evidence it carries any behaviour at all.
        if (hook != null) {
            state?.scripted = true
        } else if (events.isEmpty()) {
            state?.scripted = false
        }
        // An op hook additionally makes the component's options offerable, so it needs
        // recording where [ComponentState.optionAt] can see it. Falls back to the cache
        // hook rather than to false, because unbinding the script's hook leaves whatever
        // the definition declared still in place.
        if (event == InterfaceEvent.OPTION.boundKey) {
            state?.optionHook = hook != null || state.definition.params != null
        }
    }

    val stats: MutableMap<Int, Int> = LinkedHashMap()

    var defaultStat: Int = 0

    fun stat(id: Int): Int = stats[id] ?: defaultStat

    fun clearStats() {
        stats.clear()
        defaultStat = 0
    }

    fun variables(space: VarSpace): Map<Int, Int> = when (space) {
        VarSpace.VARP -> varps
        VarSpace.VARBIT -> varbits
        VarSpace.VARC -> varcs
        else -> emptyMap()
    }

    fun variableStrings(): Map<Int, String> = varcStrings

    fun clearVariables() {
        varps.clear()
        varbits.clear()
        varcs.clear()
        varcStrings.clear()
    }

    override fun varp(id: Int) = varps[id] ?: 0

    override fun setVarp(id: Int, value: Int) {
        varps[id] = value
        onChanged?.invoke()
        onVarpWritten?.invoke(id)
    }

    override fun varbit(id: Int) = varbits[id] ?: 0

    override fun setVarbit(id: Int, value: Int) {
        varbits[id] = value
        onChanged?.invoke()
        onVarpWritten?.invoke(id)
    }

    override fun varc(id: Int) = varcs[id] ?: 0

    override fun setVarc(id: Int, value: Int) {
        varcs[id] = value
        onVarcWritten?.invoke(id)
    }

    override fun varcString(id: Int) = varcStrings[id] ?: ""

    override fun setVarcString(id: Int, value: String) {
        varcStrings[id] = value
        onVarcStringWritten?.invoke(id)
    }

    override fun onError(message: String) {
        log.add("error: $message")
    }

    companion object {
        /** Client key code for Shift, from the client's `REMAPPED_KEYS` table. */
        const val KEY_SHIFT = 81

        /** Client key code for Ctrl. */
        const val KEY_CTRL = 82

        /** Client key code for Alt. */
        const val KEY_ALT = 86

        /**
         * The event suffix [setHook] files an opcode under: the opcode name minus
         * its `IF_SETON`/`CC_SETON` prefix. `CC_SETONMOUSEOVER` and `IF_SETONMOUSEOVER`
         * both reduce to `MOUSEOVER`, which is what makes the two forms of a hook
         * setter interchangeable.
         */
        fun eventSuffix(name: String): String = name
            .removePrefix("CC_")
            .removePrefix("IF_")
            .removePrefix("SETON")

        /**
         * Definition hook field to the event suffix of the opcode that overrides it.
         *
         * Recovered by following each `IF_SETON*` opcode through the client's
         * dispatch table to the `Component` field it assigns. The decoder's field
         * names do not follow Jagex's: `IF_SETONMOUSEREPEAT` writes `popupScript`,
         * `IF_SETONTARGETLEAVE` writes `hookParams`, `IF_SETONOP` writes `params`,
         * `IF_SETONOPT` writes `onTargetLeave`, and `IF_SETONINVTRANSMIT` writes the
         * field the cache decoder calls `mouseLeaveScript` rather than `onMouseLeaveScript`.
         * The definition field is the stable identity, so it is the key.
         */
        val HOOK_SUFFIX_BY_FIELD: Map<String, String> = mapOf(
            "onMouseOver" to "MOUSEOVER",
            "onMouseLeaveScript" to "MOUSELEAVE",
            "onClick" to "CLICK",
            "popupScript" to "MOUSEREPEAT",
            "mouseLeaveScript" to "INVTRANSMIT",
            "onClickRepeat" to "CLICKREPEAT",
            "onRelease" to "RELEASE",
            "onHold" to "HOLD",
            "onDrag" to "DRAG",
            "onDragComplete" to "DRAGCOMPLETE",
            "onMouseMove" to "SCROLLWHEEL",
            "onKey" to "VARCTRANSMIT",
            "onScrollWheel" to "VARCSTRTRANSMIT",
            "onVarpTransmit" to "VARTRANSMIT",
            "onStatTransmit" to "STATTRANSMIT",
            "onTimer" to "TIMER",
            "onTargetEnter" to "TARGETENTER",
            "onTargetLeave" to "OPT",
            "hookParams" to "TARGETLEAVE",
            "params" to "OP",
            // The cache has no field for typed keys; `IF_SETONKEY` is the only way
            // to bind one, so the client field name is carried here for symmetry.
            "onKeyType" to "KEY",
        )
    }
}
