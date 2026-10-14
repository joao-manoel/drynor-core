package org.darkan.tools.rsinterface

import org.darkan.cs2.vm.Cs2Event
import org.darkan.cs2.vm.Cs2HookDispatcher
import org.lwjgl.glfw.GLFW
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook

/**
 * Every interaction a component hook can be attached to.
 *
 * The entries are named for what the client *does*, not for the opcode or the
 * cache field, because neither of those survived the client's own renaming. The
 * mapping was recovered by following each `IF_SETON*` opcode through the
 * client's dispatch table to the field it writes, and each field from there to
 * the branch of `InterfaceRenderer.processInterfaceCycle` that fires it. The
 * decoder's field names are the misleading half:
 *
 * - `IF_SETONMOUSEOVER` writes `onMouseOver` ([MOUSE_ENTER]) while
 *   `IF_SETONMOUSEREPEAT` writes `popupScript` ([MOUSE_OVER]).
 * - `IF_SETONINVTRANSMIT` writes `mouseLeaveScript`, which the client fires off
 *   inventory updates ([INV_TRANSMIT]); the hover-leave hook is
 *   `onMouseLeaveScript`, written by `IF_SETONMOUSELEAVE` ([MOUSE_EXIT]).
 * - `IF_SETONTARGETLEAVE` writes `hookParams` ([DESELECT]) and `IF_SETONOPT`
 *   writes `onTargetLeave` ([TARGET_LEAVE]).
 *
 * [field] is the [InterfaceComponentDefinition] property the cache decodes the
 * hook into, and [boundKey] is the key a runtime-bound hook is filed under in
 * [InterfaceCs2Host.boundHooks]. A runtime-bound hook always wins, which is what
 * lets a script replace a component's cache behaviour.
 */
enum class InterfaceEvent(
    val field: String?,
    val boundKey: String,
    private val hookAccessor: (InterfaceComponentDefinition) -> InterfaceComponentHook?,
    internal val transmitFilter: ((InterfaceComponentDefinition) -> IntArray?)? = null,
) {
    /** Runs once when the interface opens, before anything is drawn. */
    LOAD("onLoadScript", "LOAD", { it.onLoadScript }),

    /** The pointer entered the component. */
    MOUSE_ENTER("onMouseOver", "MOUSEOVER", { it.onMouseOver }),

    /** Fires every client cycle the pointer stays inside the component. */
    MOUSE_OVER("popupScript", "MOUSEREPEAT", { it.popupScript }),

    /** The pointer left the component. */
    MOUSE_EXIT("onMouseLeaveScript", "MOUSELEAVE", { it.onMouseLeaveScript }),

    /**
     * The left button went down over the component. This is the client's "click":
     * it fires on press, not on release - see [InterfaceInteraction.clickOnRelease].
     */
    MOUSE_PRESS("onClick", "CLICK", { it.onClick }),

    /** Fires every cycle the button stays down with the pointer still inside. */
    CLICK_REPEAT("onClickRepeat", "CLICKREPEAT", { it.onClickRepeat }),

    /** The button came up, or the pointer left while it was down. */
    RELEASE("onRelease", "RELEASE", { it.onRelease }),

    /** Fires every cycle the button is down over the component. */
    HOLD("onHold", "HOLD", { it.onHold }),

    /** Fires every cycle a drag of this component is past its dead zone. */
    DRAG("onDrag", "DRAG", { it.onDrag }),

    /** The drag was dropped; the event carries the target component and slot. */
    DRAG_COMPLETE("onDragComplete", "DRAGCOMPLETE", { it.onDragComplete }),

    /**
     * The wheel turned over the component. The delta arrives in the event's
     * `mouseY`, which is how the client smuggles it into a hook that otherwise
     * carries a pointer position.
     */
    MOUSE_WHEEL("onMouseMove", "SCROLLWHEEL", { it.onMouseMove }),

    /** A right-click option was chosen; the event carries the option and its label. */
    OPTION("params", "OP", { it.params }),

    /** The component was selected as the source of a "use on" interaction. */
    TARGET_ENTER("onTargetEnter", "TARGETENTER", { it.onTargetEnter }),

    /** A use-on selection was used on this component; the client fires it on the target. */
    TARGET_LEAVE("onTargetLeave", "OPT", { it.onTargetLeave }),

    /** The use-on selection was cancelled; the client fires it from `cancelInterfaceSelection`. */
    DESELECT("hookParams", "TARGETLEAVE", { it.hookParams }),

    /** Fires every client cycle. */
    TIMER("onTimer", "TIMER", { it.onTimer }),

    /** A varp the component listens for changed. */
    VARP_TRANSMIT("onVarpTransmit", "VARTRANSMIT", { it.onVarpTransmit }, { it.varps }),

    /** A skill the component listens for changed. */
    STAT_TRANSMIT(
        "onStatTransmit",
        "STATTRANSMIT",
        { it.onStatTransmit },
        { it.statTransmitFilter },
    ),

    /**
     * An item container the component listens for changed. Bound by
     * `IF_SETONINVTRANSMIT`, whose field the cache decoder calls `mouseLeaveScript`
     * despite it having nothing to do with the mouse.
     */
    INV_TRANSMIT(
        "mouseLeaveScript",
        "INVTRANSMIT",
        { it.mouseLeaveScript },
        { it.mouseLeaveArrayParams },
    ),

    /**
     * A client int var the component listens for changed. Bound by
     * `IF_SETONVARCTRANSMIT`, and the filter array the decoder calls `keyPressArray`
     * holds varc ids.
     */
    VARC_TRANSMIT("onKey", "VARCTRANSMIT", { it.onKey }, { it.keyPressArray }),

    /**
     * A client string var the component listens for changed. Bound by
     * `IF_SETONVARCSTRTRANSMIT`, and the filter the decoder calls `mouseWheelArray`
     * holds varcstring ids - the wheel goes to [MOUSE_WHEEL], not here.
     */
    VARC_STRING_TRANSMIT(
        "onScrollWheel",
        "VARCSTRTRANSMIT",
        { it.onScrollWheel },
        { it.mouseWheelArray },
    ),

    /**
     * A key was typed. The cache decodes no such field - the client's `onKeyType`
     * only ever comes from `IF_SETONKEY` - so this event exists purely as a
     * runtime hook and [definitionHook] always answers null.
     */
    KEY("onKeyType", "KEY", { null }),
    ;

    /** The hook the cache defined for this event on [definition], if any. */
    fun definitionHook(definition: InterfaceComponentDefinition): InterfaceComponentHook? =
        hookAccessor(definition)
}

/** Where an option in a component's right-click menu came from. */
enum class OptionKind {
    /** One of the ten numbered options; dispatches [InterfaceEvent.OPTION]. */
    OPTION,

    /** The "use this on something" entry; selects the component for use. */
    USE,

    /** The bit-0 "Continue" entry, which the client answers with a packet. */
    CONTINUE,
}

/**
 * One entry of a component's right-click menu, as the client would build it.
 *
 * [option] is 1-based because that is the number the client hands to the
 * `clickedOption` hook argument and to the `IF_OP1..IF_OP10` packets; it is 0 for
 * the entries that are not numbered options.
 */
data class ComponentOption(
    val component: ComponentState,
    val option: Int,
    val label: String,
    val kind: OptionKind = OptionKind.OPTION,
    val slot: Int = -1,
    /** Cursor the client shows while this entry is highlighted, or -1. */
    val cursor: Int = -1,
) {
    /** The full line the client draws: the option, then the component's op base. */
    val text: String get() = if (component.opBase.isBlank()) label else "$label ${component.opBase}"
}

/** The rectangle an interface is drawn into, in window pixels. */
data class InterfaceViewport(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(pointX: Int, pointY: Int): Boolean =
        pointX >= x && pointY >= y && pointX < x + width && pointY < y + height
}

/**
 * Raw pointer state for one frame, in window pixels.
 *
 * [wheel] follows GLFW's sign - positive is a scroll away from the user - and is
 * negated on the way into hooks, because the client counts the other way.
 */
data class InterfacePointer(
    val x: Int,
    val y: Int,
    val left: Boolean = false,
    val right: Boolean = false,
    val middle: Boolean = false,
    val wheel: Int = 0,
)

/** A drag in progress. */
class InterfaceDrag internal constructor(
    /** The component picked up. */
    val source: ComponentState,
    /** Pointer offset inside [source] when the drag started. */
    val offsetX: Int,
    val offsetY: Int,
    /**
     * Component the drag is confined to, or null when it may go anywhere.
     *
     * The client's `selectedComponent`: the drag position is clamped so [source] stays
     * inside this box, and the coordinates the drag hooks receive are measured from
     * its content origin rather than from the screen. For a scrollbar thumb it is the
     * track, which is what turns a drag into a scroll offset.
     */
    val bounds: ComponentState? = null,
) {
    /** Component currently under the pointer that will accept the drop, or null. */
    var target: ComponentState? = null
        internal set

    /** Slot within [target] under the pointer, or -1. */
    var targetSlot: Int = -1
        internal set

    /** Slot within [source] the drag started on, or -1. */
    var sourceSlot: Int = -1
        internal set

    /**
     * True once the pointer has both been held past the source's dead time and
     * travelled past its dead zone. Until then the client treats the gesture as a
     * click and fires no drag hooks.
     */
    var active: Boolean = false
        internal set

    /** Pointer position, in window pixels. */
    var pointerX: Int = 0
        internal set
    var pointerY: Int = 0
        internal set

    internal var cycles: Int = 0

    /**
     * Screen position [source] was drawn at when the drag began, the client's
     * `dragStartX`/`dragStartY`.
     *
     * The dead zone is measured against how far the *component* has been moved, not
     * how far the pointer has travelled. The two differ whenever a script picks the
     * drag up with `CC_DRAGPICKUP` from somewhere other than the component itself -
     * a scrollbar's track does exactly that, and against pointer travel such a drag
     * would never clear its dead zone at all.
     */
    internal var startX: Int = 0
    internal var startY: Int = 0
}

/** One hook dispatch, recorded for the editor's event log. */
data class InteractionTrace(
    val event: InterfaceEvent,
    /** Component index within its interface. */
    val component: Int,
    /** Packed component hash the hook ran against. */
    val hash: Int,
    /** Script the hook ran, or -1 when no hook was attached. */
    val script: Int,
    /** True when the hook came from a script rather than from the cache. */
    val bound: Boolean,
    /** Whatever the virtual machine reported, or null when the script completed. */
    val error: String? = null,
)

/**
 * Drives a live interface from raw pointer and keyboard input.
 *
 * This is the client's `InterfaceRenderer.processInterfaceCycle` and
 * `Client.processDrag` with the drawing taken out: it owns the hover, press,
 * drag, option-menu and key-binding state the client keeps, and turns pointer
 * movement into the same sequence of CS2 hook dispatches the client would
 * produce. Everything it needs from the outside arrives through [update], [key]
 * and the transmit entry points, so it holds no window, no GL and no ImGui - the
 * editor's UI reads the state back out and draws it.
 *
 * ### Faithfulness notes
 *
 * A few behaviours are surprising enough to be worth stating up front, because
 * they are the client's and not mistakes:
 *
 * - **Click fires on press.** The client's `onClick` runs the moment the button
 *   goes down, before any release. [clickOnRelease] switches to the
 *   press-and-release-on-the-same-component behaviour a UI toolkit would use.
 * - **Hooks fire for the whole hover chain.** Every component the pointer is
 *   inside gets its own enter/over/leave and press/hold/release, not just the
 *   topmost one.
 * - **`noClickThrough` swallows.** A component with it set hides everything drawn
 *   behind it from the pointer. The cache decoder calls the field `disableHover`,
 *   which describes the effect on the components behind rather than on itself.
 * - **Repeat hooks run on the client cycle**, not per frame; see [CYCLE_MILLIS].
 * - **The wheel goes to `onMouseMove`.** There is no wheel filter in this
 *   revision: `onScrollWheel` and its `mouseWheelArray` are a client string var
 *   transmit hook, reached through [varcStringChanged].
 *
 * @param instances supplies the interface being interacted with; may return a
 *   different instance at any time, and the interaction state resets when it does
 * @param dispatcher runs the hooks
 * @param host the CS2 host backing [dispatcher]; its active-component register is
 *   pointed at each component before its hook runs, exactly as a `CC_*` script
 *   expects, and its var-write callbacks are wired to the transmit entry points
 */
class InterfaceInteraction(
    private val instances: () -> InterfaceInstance?,
    private val dispatcher: Cs2HookDispatcher,
    private val host: InterfaceCs2Host,
) {

    /**
     * When false, input is tracked but no hook is dispatched. This is what lets a
     * developer drag components around in the editor without the interface
     * reacting to every press.
     */
    var liveMode: Boolean = true

    /**
     * Fire [InterfaceEvent.MOUSE_PRESS] on release over the pressed component
     * rather than on press. Off by default because the client fires on press.
     */
    var clickOnRelease: Boolean = false

    /**
     * Scroll the nearest scrollable container directly on a wheel event.
     *
     * The client does not do this: interface scrolling lives entirely in the
     * scrollbar CS2 scripts, and the only thing the client scrolls itself is the
     * developer console, five lines at a time. An interface under test often has
     * no scrollbar wired up yet, so the editor moves the container itself.
     */
    var directScroll: Boolean = true

    /** Pixels [directScroll] moves per wheel notch. */
    var scrollStep: Int = DEFAULT_SCROLL_STEP

    /**
     * Resolves the item slot under a pointer position given in component-local
     * pixels. Defaults to [ComponentState.slotAt], which answers -1 unless the
     * component has been given a slot grid.
     */
    var slotResolver: (ComponentState, Int, Int) -> Int = { component, localX, localY ->
        component.slotAt(localX, localY)
    }

    /** Most recent dispatches, oldest first, capped at [traceCapacity]. */
    val trace: List<InteractionTrace> get() = traceLog

    /** How many dispatches [trace] keeps. */
    var traceCapacity: Int = DEFAULT_TRACE_CAPACITY

    /** Components the pointer is inside, back to front. */
    val hovered: List<ComponentState> get() = hoverChain

    /** Components the button went down on, back to front. */
    val pressedChain: List<ComponentState> get() = pressChain

    /** The topmost interactive component the button went down on, if any. */
    val pressed: ComponentState?
        get() = pressChain.lastOrNull { it.interactive && it.type != ComponentType.CONTAINER }
            ?: pressChain.lastOrNull()

    /** How long the current press has been held, in milliseconds; 0 when not pressed. */
    var pressMillis: Long = 0
        private set

    /** Component that receives keys with no binding of their own. */
    var focused: ComponentState? = null
        private set

    /** The drag in progress, or null. */
    var drag: InterfaceDrag? = null
        private set

    /** Component selected as the source of a "use on", or null. */
    var selected: ComponentState? = null
        private set

    /** Slot within [selected] that was selected, or -1. */
    var selectedSlot: Int = -1
        private set

    /** The open right-click menu, top entry first, or empty when closed. */
    val menu: List<ComponentOption> get() = menuEntries

    /** True while [menu] is open; the client suppresses all hover work meanwhile. */
    val menuOpen: Boolean get() = menuEntries.isNotEmpty()

    /** Window position the menu was opened at. */
    var menuX: Int = 0
        private set
    var menuY: Int = 0
        private set

    /** The viewport passed to the last [update]. */
    var viewport: InterfaceViewport = InterfaceViewport(0, 0, 0, 0)
        private set

    /** The pointer passed to the last [update]. */
    var pointer: InterfacePointer = InterfacePointer(-1, -1)
        private set

    /** True when the last [update] saw the pointer at a new position. */
    var pointerMoved: Boolean = false
        private set

    private val traceLog = ArrayList<InteractionTrace>()
    private var hoverChain: List<ComponentState> = emptyList()
    private var pressChain: List<ComponentState> = emptyList()
    private var menuEntries: List<ComponentOption> = emptyList()

    private var deferredOption: ComponentOption? = null
    private var attached: InterfaceInstance? = null
    private var transmitDepth: Int = 0
    private var cycleAccumulator: Long = 0
    private var lastLeft = false
    private var lastRight = false

    init {
        // The host is the only thing that sees every var write, and a transmit hook
        // is the only reason anyone cares, so route writes straight back here.
        host.onVarpWritten = ::varpChanged
        host.onVarcWritten = ::varcChanged
        host.onVarcStringWritten = ::varcStringChanged
        host.onDragPickup = ::dragPickup
    }

    // ------------------------------------------------------------ frame input

    /**
     * Advances the interaction by one frame.
     *
     * [deltaMillis] drives everything that repeats: the client runs its repeat
     * hooks once per cycle rather than once per frame, so a fast editor frame rate
     * does not multiply how often a held button fires.
     */
    fun update(viewport: InterfaceViewport, pointer: InterfacePointer, deltaMillis: Long) {
        this.viewport = viewport
        val previous = this.pointer
        this.pointer = pointer

        val instance = instances()
        if (instance !== attached) {
            reset()
            attached = instance
        }
        if (instance == null) {
            return
        }

        host.mouseX = pointer.x - viewport.x
        host.mouseY = pointer.y - viewport.y
        host.mouseLeft = pointer.left
        host.mouseRight = pointer.right
        host.mouseMiddle = pointer.middle

        // Cap the catch-up so a stalled frame cannot fire hundreds of repeats at
        // once; the client simply misses cycles in the same situation.
        cycleAccumulator += deltaMillis.coerceAtLeast(0)
        val cycles = (cycleAccumulator / CYCLE_MILLIS).toInt().coerceAtMost(MAX_CATCH_UP_CYCLES)
        cycleAccumulator %= CYCLE_MILLIS

        if (!liveMode) {
            // Drop the hover and press flags rather than leaving components stuck
            // in a state nothing will now clear, but do not fire the leave hooks -
            // suppressing dispatch is the whole point of the flag.
            reset()
            lastLeft = pointer.left
            lastRight = pointer.right
            return
        }

        updateHover(instance, pointer)
        updateButtons(pointer, cycles, deltaMillis)
        updateDrag(instance, pointer, cycles)
        if (pointer.wheel != 0) {
            wheel(instance, pointer.wheel)
        }
        repeat(cycles) {
            // The client's "still hovered" hook, fired every cycle rather than only
            // on the frames the pointer happens to move.
            for (component in hoverChain) {
                dispatch(component, InterfaceEvent.MOUSE_OVER, pointerEvent(component, pointer))
            }
            for (component in instance.components) {
                if (!component.hidden) {
                    dispatch(component, InterfaceEvent.TIMER, Cs2Event(source = component.hash))
                }
            }
            cooldownTick(instance)
        }

        // Kept so a caller can tell a genuine move from a repeated frame; the
        // hooks themselves read the position off the event they are handed.
        pointerMoved = previous.x != pointer.x || previous.y != pointer.y
        lastLeft = pointer.left
        lastRight = pointer.right
    }

    /** Forgets all interaction state; call when the interface changes underneath. */
    fun reset() {
        hoverChain.forEach { it.hovered = false }
        pressChain.forEach { it.pressed = false }
        hoverChain = emptyList()
        pressChain = emptyList()
        menuEntries = emptyList()
        deferredOption = null
        drag = null
        selected = null
        selectedSlot = -1
        focused = null
        pressMillis = 0
        cycleAccumulator = 0
        lastLeft = false
        lastRight = false
    }

    /** Empties the event log. */
    fun clearTrace() {
        traceLog.clear()
    }

    // ------------------------------------------------------------------ hover

    private fun updateHover(instance: InterfaceInstance, pointer: InterfacePointer) {
        val chain = if (menuOpen || !viewport.contains(pointer.x, pointer.y)) {
            emptyList()
        } else {
            instance.hoverChain(
                viewport.x, viewport.y, viewport.width, viewport.height,
                pointer.x, pointer.y,
            )
        }

        for (component in hoverChain) {
            if (chain.none { it === component }) {
                component.hovered = false
                dispatch(component, InterfaceEvent.MOUSE_EXIT, pointerEvent(component, pointer))
            }
        }
        for (component in chain) {
            if (!component.hovered) {
                component.hovered = true
                dispatch(component, InterfaceEvent.MOUSE_ENTER, pointerEvent(component, pointer))
            }
        }
        hoverChain = chain
    }

    // ---------------------------------------------------------------- buttons

    private fun updateButtons(
        pointer: InterfacePointer,
        cycles: Int,
        deltaMillis: Long,
    ) {
        if (pointer.right && !lastRight && openMenu(pointer.x, pointer.y)) {
            return
        }
        if (pointer.left && !lastLeft && menuOpen) {
            // A left click anywhere while the menu is up takes the default entry,
            // which is the one the client drew at the top.
            menuEntries.firstOrNull()?.let { chooseOption(it) }
            closeMenu()
            return
        }

        if (pointer.left && !lastLeft) {
            // The client builds its menu while it draws and only drains the hook queue
            // afterwards, so the entry a left click resolves to is the one that existed
            // *before* any press hook ran. Resolving it after [beginPress] would consult
            // a hover chain the presses have already changed - a popup's modal catcher
            // hides the very row that was clicked.
            val choice = defaultOption()
            beginPress(pointer)
            if (drag == null) {
                choice?.let { chooseOption(it, alreadyPressed = true) }
            } else {
                // The client's `isDeferring`: a press that picked a drag up holds its
                // menu entry back, and `processDrag` fires it on release only when the
                // gesture never cleared the dead zone - a click that happened to land
                // on something draggable.
                deferredOption = choice
            }
        }
        if (pointer.left) {
            pressMillis += deltaMillis.coerceAtLeast(0)
            // A live drag clears the client's `leftClick` for every component in the
            // pass, so nothing repeats or holds until the drag is dropped.
            if (drag == null) {
                repeat(cycles) {
                    for (component in pressChain) {
                        if (hoverChain.any { it === component }) {
                            dispatch(component, InterfaceEvent.CLICK_REPEAT, pointerEvent(component, pointer))
                        }
                    }
                    for (component in hoverChain) {
                        dispatch(component, InterfaceEvent.HOLD, pointerEvent(component, pointer))
                    }
                }
            }
        }

        // The client releases a component the moment the pointer leaves it, not
        // only when the button comes up: `isPressed && !leftClick` is false as soon
        // as the pointer is outside, because `leftClick` includes the bounds test.
        val released = pressChain.filter { state -> !pointer.left || hoverChain.none { it === state } }
        if (released.isNotEmpty()) {
            for (component in released) {
                component.pressed = false
                val stillInside = hoverChain.any { it === component }
                if (clickOnRelease && !pointer.left && stillInside) {
                    dispatch(component, InterfaceEvent.MOUSE_PRESS, pointerEvent(component, pointer))
                }
                dispatch(component, InterfaceEvent.RELEASE, pointerEvent(component, pointer))
            }
            pressChain = pressChain.filter { state -> released.none { it === state } }
        }
        if (!pointer.left) {
            pressMillis = 0
        }
    }

    /**
     * Presses everything under the pointer, in draw order, picking up a drag on the
     * way as the client's pass does.
     *
     * The interleaving matters and is not an implementation detail. The client calls
     * `startDragging` before it decides whether to queue the component's own click,
     * and then suppresses the click of *every* component it reaches while
     * `draggingComponent` is set - which, drawn back to front, is the drag source and
     * everything on top of it. Dispatch all the presses first and a popup's modal
     * click-catcher, which is drawn above the popup, fires and closes the popup out
     * from under a drag that has only just begun. That is precisely what stops a
     * dropdown's scrollbar from working.
     */
    private fun beginPress(pointer: InterfacePointer) {
        pressChain = hoverChain
        pressMillis = 0
        focused = pressed
        for (component in pressChain) {
            component.pressed = true
            if (drag == null && component.draggable) {
                beginDrag(component, pointer.x - component.screenX, pointer.y - component.screenY)
            }
            if (drag != null) {
                continue
            }
            if (!clickOnRelease) {
                // The press itself may pick the drag up, by way of a hook that calls
                // `CC_DRAGPICKUP` - a scrollbar's track hands the drag to the thumb
                // exactly that way - so the next component sees `drag` set.
                dispatch(component, InterfaceEvent.MOUSE_PRESS, pointerEvent(component, pointer))
            }
        }
    }

    // ------------------------------------------------------------------- drag

    /**
     * Starts a drag of [component] held at ([offsetX], [offsetY]) inside it, the
     * client's `startDragging`, and the entry point `IF_DRAGPICKUP` /
     * `CC_DRAGPICKUP` reaches through [InterfaceCs2Host.onDragPickup].
     *
     * Nothing is dispatched yet: the gesture is still a click until it clears the
     * source's dead time and dead zone. A component with no drag depth and no
     * [ComponentState.depthChild] cannot be picked up at all, as in the client.
     */
    fun dragPickup(component: ComponentState, offsetX: Int, offsetY: Int) {
        if (!component.draggable) {
            return
        }
        beginDrag(component, offsetX, offsetY)
    }

    private fun beginDrag(source: ComponentState, offsetX: Int, offsetY: Int) {
        if (drag != null || menuOpen) {
            return
        }
        drag = InterfaceDrag(
            source = source,
            offsetX = offsetX,
            offsetY = offsetY,
            bounds = dragBounds(source),
        ).apply {
            startX = source.screenX
            startY = source.screenY
            pointerX = pointer.x
            pointerY = pointer.y
            sourceSlot = slotOf(source, pointer.x, pointer.y)
        }
    }

    /**
     * The component a drag of [source] is confined to, transcribing the client's
     * `Component.getDepthChild`: the drag depth in the events bitmask counts that many
     * parents up, an unbounded drag is confined to nothing, and a script-supplied
     * [ComponentState.depthChild] is the fallback when the bitmask grants no depth.
     */
    private fun dragBounds(source: ComponentState): ComponentState? {
        if (source.dragUnbounded) {
            return null
        }
        val depth = source.dragDepth
        if (depth == 0) {
            return source.depthChild
        }
        val instance = instances() ?: return source.depthChild
        var current: ComponentState = source
        repeat(depth) {
            current = instance.parentOf(current) ?: return null
        }
        return current
    }

    private fun updateDrag(instance: InterfaceInstance, pointer: InterfacePointer, cycles: Int) {
        val current = drag ?: return
        current.cycles += cycles
        current.pointerX = pointer.x
        current.pointerY = pointer.y

        // Measured against where the component now sits, not where the pointer has
        // been: see [InterfaceDrag.startX].
        val positionX = dragPositionX(current)
        val positionY = dragPositionY(current)
        if (!current.active && current.cycles > current.source.dragDeadTime) {
            val travelX = positionX - current.startX
            val travelY = positionY - current.startY
            val zone = current.source.dragDeadzone
            if (travelX > zone || travelX < -zone || travelY > zone || travelY < -zone) {
                current.active = true
            }
        }

        val target = hoverChain.lastOrNull { it !== current.source && it.dropTarget }
        current.target = target
        current.targetSlot = if (target == null) -1 else slotOf(target, pointer.x, pointer.y)

        if (pointer.left) {
            if (current.active) {
                repeat(cycles) {
                    dispatch(current.source, InterfaceEvent.DRAG, dragEvent(current))
                }
            }
            return
        }

        if (current.active) {
            dispatch(current.source, InterfaceEvent.DRAG_COMPLETE, dragEvent(current))
            // A drop script can move a component between containers, so the layout
            // the renderer cached is no longer trustworthy.
            instance.invalidateLayout()
        } else {
            // The gesture never became a drag, so it was a click after all and the menu
            // entry held back at press time is due now. The client reaches this through
            // `processDrag` -> `processMenuSelection` on exactly the same condition.
            deferredOption?.let { chooseOption(it, alreadyPressed = true) }
        }
        deferredOption = null
        drag = null
    }

    /**
     * The drag hooks' payload, transcribing the coordinates the client's `processDrag`
     * hands them: the dragged component's clamped position expressed relative to the
     * content origin of whatever confines the drag, and the raw screen position when
     * nothing does.
     *
     * These are *not* pointer coordinates, which is easy to get wrong because every
     * other hook's `mouseX`/`mouseY` are. A scrollbar's drag script reads them as the
     * thumb's offset down its track and scales that straight into a scroll position,
     * so handing it a pointer offset scrolls to a position unrelated to the thumb.
     */
    private fun dragEvent(current: InterfaceDrag): Cs2Event {
        val bounds = current.bounds
        var x = dragPositionX(current)
        var y = dragPositionY(current)
        if (bounds != null) {
            x += bounds.scrollX - bounds.screenX
            y += bounds.scrollY - bounds.screenY
        }
        return Cs2Event(
            mouseX = x,
            mouseY = y,
            source = current.source.hash,
            sourceSlot = current.sourceSlot,
            target = current.target?.hash ?: -1,
            targetSlot = current.targetSlot,
            optionName = current.source.opBase,
        )
    }

    /**
     * Screen X the dragged component is drawn at: the pointer less the grab offset,
     * kept inside [InterfaceDrag.bounds] so the whole component stays in the box.
     */
    private fun dragPositionX(current: InterfaceDrag): Int {
        val position = current.pointerX - current.offsetX
        val bounds = current.bounds ?: return position
        val last = bounds.screenX + bounds.width - current.source.width
        return position.coerceIn(bounds.screenX, maxOf(bounds.screenX, last))
    }

    private fun dragPositionY(current: InterfaceDrag): Int {
        val position = current.pointerY - current.offsetY
        val bounds = current.bounds ?: return position
        val last = bounds.screenY + bounds.height - current.source.height
        return position.coerceIn(bounds.screenY, maxOf(bounds.screenY, last))
    }

    // ------------------------------------------------------------------ wheel

    private fun wheel(instance: InterfaceInstance, delta: Int) {
        var handled = false
        for (component in hoverChain) {
            val ran = dispatch(
                component,
                InterfaceEvent.MOUSE_WHEEL,
                Cs2Event(
                    mouseX = pointer.x - component.screenX,
                    // The client puts the wheel delta where the hook's Y would go,
                    // counting positive towards the user - the opposite of GLFW.
                    mouseY = -delta,
                    source = component.hash,
                    sourceSlot = slotOf(component, pointer.x, pointer.y),
                ),
            )
            handled = handled || ran
        }
        // A scrollbar's own wheel hook has already moved the list, and it moved it by
        // the amount that keeps the thumb in step. Scrolling again here would double
        // every notch and leave the thumb behind, so the editor's stand-in only runs
        // when nothing under the pointer answered the wheel at all.
        if (!directScroll || handled) {
            return
        }
        val under = hoverChain.lastOrNull() ?: return
        val scroller = instance.scrollableAncestor(under) ?: return
        instance.scrollBy(scroller, 0, -delta * scrollStep)
    }

    // --------------------------------------------------------------- keyboard

    /**
     * Handles a key press or auto-repeat.
     *
     * [code] is a GLFW key code and [modifiers] a GLFW modifier mask; both are
     * translated to the client's own numbering by [InterfaceKeys] before anything
     * is matched, because a component's bindings are stored in the client's codes.
     *
     * Key bindings come first: the client turns a bound key straight into the
     * corresponding option, which is how a keyboard shortcut on a button reaches
     * the same hook the right-click menu would. Anything left over goes to the
     * components with a [InterfaceEvent.KEY] hook, and failing that to [focused].
     */
    fun key(code: Int, char: Char, modifiers: Int, repeat: Boolean = false) {
        val clientCode = InterfaceKeys.fromGlfw(code)
        if (clientCode != 0) {
            host.heldKeys.add(clientCode)
        }
        if (!liveMode) {
            return
        }
        val instance = instances() ?: return
        val mask = InterfaceKeys.modifierMask(modifiers)

        var consumed = false
        for (component in instance.components) {
            if (!component.clickable || component.hidden) {
                continue
            }
            val index = matchOptionKey(component, clientCode, char, mask, repeat)
            if (index >= 0) {
                consumed = true
                triggerOptionKey(component, index)
            }
        }
        if (consumed) {
            return
        }

        val event = Cs2Event(keyCode = clientCode, keyChar = char.code)
        var delivered = false
        for (component in instance.components) {
            if (host.boundHooks[component.hash]?.containsKey(InterfaceEvent.KEY.boundKey) == true) {
                delivered = true
                dispatch(component, InterfaceEvent.KEY, event.copy(source = component.hash))
            }
        }
        if (!delivered) {
            focused?.let { dispatch(it, InterfaceEvent.KEY, event.copy(source = it.hash)) }
        }
    }

    /** Releases a GLFW key, clearing any cooldown its bindings were holding. */
    fun keyReleased(code: Int) {
        val clientCode = InterfaceKeys.fromGlfw(code)
        if (clientCode == 0) {
            return
        }
        host.heldKeys.remove(clientCode)
        val instance = instances() ?: return
        for (component in instance.components) {
            for (index in component.optionKeyCodes.indices) {
                if (component.optionKeyCodes[index] == clientCode) {
                    component.optionKeyCooldowns[index] = 0
                }
            }
        }
    }

    /** Points keyless input at [component]; pass null to drop focus. */
    fun focus(component: ComponentState?) {
        focused = component
    }

    /**
     * Which option binding, if any, [clientCode]/[char] triggers on [component],
     * or -1 for none.
     *
     * Transcribes the key block of `processInterfaceCycle`: a typed character wins
     * over a key code, a binding still on cooldown matches nothing, and a binding
     * with a modifier mask needs every bit of that mask satisfied. A binding whose
     * repeat count is zero is one-shot, so an auto-repeat does not re-fire it.
     */
    private fun matchOptionKey(
        component: ComponentState,
        clientCode: Int,
        char: Char,
        mask: Int,
        repeat: Boolean,
    ): Int {
        for (index in component.optionKeyCodes.indices) {
            if (component.optionKeyCooldowns[index] != 0) {
                continue
            }
            if (component.optionKeyChars[index] > 0 && component.optionKeyChars[index] == char.code) {
                return index
            }
            val code = component.optionKeyCodes[index]
            if (code == 0 || code != clientCode) {
                continue
            }
            if (repeat && component.optionKeyRepeats[index] == 0) {
                continue
            }
            val required = component.optionKeyModifiers[index]
            if (required == 0 || (required and mask) == required) {
                return index
            }
        }
        return -1
    }

    /**
     * Runs the option a key binding maps to. Index 10 is the client's "select for
     * use" slot rather than an option; the rest are options 1-10.
     */
    private fun triggerOptionKey(component: ComponentState, index: Int) {
        val repeats = component.optionKeyRepeats.getOrElse(index) { 0 }
        component.optionKeyCooldowns[index] = if (repeats != 0) repeats else Int.MAX_VALUE
        if (index == ComponentState.OPTION_COUNT) {
            select(component, slotOf(component, pointer.x, pointer.y))
            return
        }
        chooseOption(
            ComponentOption(
                component = component,
                option = index + 1,
                label = component.optionAt(index) ?: "",
                slot = slotOf(component, pointer.x, pointer.y),
                cursor = component.optionCursor(index),
            )
        )
    }

    /** Counts down the per-binding cooldowns the way the client's cycle counter does. */
    private fun cooldownTick(instance: InterfaceInstance) {
        for (component in instance.components) {
            for (index in component.optionKeyCooldowns.indices) {
                val cooldown = component.optionKeyCooldowns[index]
                if (cooldown in 1 until Int.MAX_VALUE) {
                    component.optionKeyCooldowns[index] = cooldown - 1
                }
            }
        }
    }

    // ----------------------------------------------------------- option menus

    /**
     * Builds the right-click menu for whatever is under ([pointX], [pointY]) and
     * opens it. Returns false, leaving the menu closed, when nothing there offers
     * an option.
     */
    fun openMenu(pointX: Int, pointY: Int): Boolean {
        val entries = ArrayList<ComponentOption>()
        for (component in hoverChain) {
            collectOptions(component, pointX, pointY, entries)
        }
        // The client adds entries bottom-up and draws the last one at the top, so
        // the display order the UI wants is the reverse of the build order.
        entries.reverse()
        menuEntries = entries
        menuX = pointX
        menuY = pointY
        return entries.isNotEmpty()
    }

    /** Closes the menu without choosing anything. */
    fun closeMenu() {
        menuEntries = emptyList()
    }

    /**
     * The entry a plain left click over the pointer invokes without ever showing a
     * menu, or null when a left click has no option to run.
     *
     * This is the client's `MiniMenu.activeEntry`, and recovering it takes three steps
     * of the client's own bookkeeping rather than "the topmost option":
     *
     * 1. Entries are collected by `interfaceComponentHovered` for every component under
     *    the pointer as the pass draws them, so in this runtime's terms: [collectOptions]
     *    over [hovered] in draw order, back to front. That is build order, oldest first.
     * 2. `MiniMenu.addEntry` prepends, and `processMenuInput` then sorts the list into
     *    priority bands by `clickType` and takes the *tail* as the active entry. Only
     *    the bands whose `clickType` is below 1000 are moved to the tail: options 1-5
     *    (57), the use verb (25) and the continue entry (30). Options 6-10 carry 1007
     *    and stay where they are, behind the permanent "Cancel" entry (1006) - which is
     *    why a component offering only options 6-10 does nothing at all on a left click,
     *    and why [leftClickable] rejects them rather than treating every option alike.
     * 3. Within the moved band the tail is the newest entry, so the winner is the last
     *    one built: the continue entry if the component has one, otherwise option 1,
     *    on the topmost component under the pointer.
     *
     * The one band that outranks all of these is the use-on-target entry (58), which
     * only exists while a component is selected for use; nothing here builds it yet.
     */
    fun defaultOption(pointX: Int = pointer.x, pointY: Int = pointer.y): ComponentOption? {
        if (menuOpen) {
            return null
        }
        val entries = ArrayList<ComponentOption>()
        for (component in hoverChain) {
            collectOptions(component, pointX, pointY, entries)
        }
        return entries.lastOrNull { leftClickable(it) }
    }

    /**
     * True when [choice] sits in a menu band a left click can reach. See [defaultOption]
     * for where the cut-off comes from.
     */
    private fun leftClickable(choice: ComponentOption): Boolean = when (choice.kind) {
        OptionKind.USE, OptionKind.CONTINUE -> true
        OptionKind.OPTION -> choice.option in 1..LEFT_CLICK_OPTIONS
    }

    /**
     * The menu [component] would contribute, without opening anything. Useful for
     * a property panel that wants to show what a component offers.
     */
    fun optionsFor(component: ComponentState, pointX: Int = pointer.x, pointY: Int = pointer.y): List<ComponentOption> {
        val entries = ArrayList<ComponentOption>()
        collectOptions(component, pointX, pointY, entries)
        entries.reverse()
        return entries
    }

    /**
     * Adds [component]'s entries in the client's build order, from
     * `interfaceComponentHovered`: options 10 down to 6, then the use verb, then
     * options 5 down to 1, then the continue entry.
     */
    private fun collectOptions(
        component: ComponentState,
        pointX: Int,
        pointY: Int,
        into: MutableList<ComponentOption>,
    ) {
        val slot = slotOf(component, pointX, pointY)
        for (index in 9 downTo 5) {
            component.optionAt(index)?.let {
                into.add(ComponentOption(component, index + 1, it, OptionKind.OPTION, slot, component.optionCursor(index)))
            }
        }
        if (component.useOptionFlags != 0 && component.useOption.isNotBlank()) {
            into.add(
                ComponentOption(component, 0, component.useOption, OptionKind.USE, slot, component.targetCursor)
            )
        }
        for (index in 4 downTo 0) {
            component.optionAt(index)?.let {
                into.add(ComponentOption(component, index + 1, it, OptionKind.OPTION, slot, component.optionCursor(index)))
            }
        }
        if (component.continueEnabled) {
            into.add(
                ComponentOption(
                    component,
                    0,
                    component.continueText ?: CONTINUE_TEXT,
                    OptionKind.CONTINUE,
                    slot,
                )
            )
        }
    }

    /**
     * Runs [choice] and closes the menu.
     *
     * A numbered option dispatches [InterfaceEvent.OPTION] with the 1-based option
     * number in `clickedOption` and the *component's op base* as `event_opbase`, which
     * is what the client's `Interface.ifOptionInteract` passes on - the menu entry hands
     * it `entry.coloredName`, and that field holds `Component.opBase` rather than the
     * option's own label.
     *
     * Components that only have a press hook fall back to [InterfaceEvent.MOUSE_PRESS]
     * carrying the same option number, so a plain button still answers its own menu
     * entry - the client reaches that hook through the press instead. Callers that have
     * already pressed the component this frame pass [alreadyPressed] to suppress the
     * fallback: the left-click path presses the whole chain first, so without it the
     * same hook would run twice off one click.
     */
    fun chooseOption(choice: ComponentOption, alreadyPressed: Boolean = false) {
        closeMenu()
        val component = choice.component
        when (choice.kind) {
            OptionKind.USE -> select(component, choice.slot)
            OptionKind.CONTINUE -> record(InterfaceEvent.OPTION, component, -1, false, null)
            OptionKind.OPTION -> {
                val event = Cs2Event(
                    mouseX = pointer.x - component.screenX,
                    mouseY = pointer.y - component.screenY,
                    source = component.hash,
                    sourceSlot = choice.slot,
                    clickedOption = choice.option,
                    optionName = component.opBase,
                )
                if (!dispatch(component, InterfaceEvent.OPTION, event) && !alreadyPressed) {
                    dispatch(component, InterfaceEvent.MOUSE_PRESS, event)
                }
            }
        }
    }

    // ------------------------------------------------------- use-on selection

    /** Selects [component] as the source of a "use on", firing its target hook. */
    fun select(component: ComponentState, slot: Int = -1) {
        cancelSelection()
        selected = component
        selectedSlot = slot
        dispatch(
            component,
            InterfaceEvent.TARGET_ENTER,
            Cs2Event(source = component.hash, sourceSlot = slot, optionName = component.opBase),
        )
    }

    /**
     * Cancels the use-on selection. The client runs the `hookParams` hook here
     * ([InterfaceEvent.DESELECT]); the editor has no use-on-target step, so
     * [InterfaceEvent.TARGET_LEAVE] is fired as well to keep that hook reachable.
     */
    fun cancelSelection() {
        val component = selected ?: return
        val slot = selectedSlot
        selected = null
        selectedSlot = -1
        val event = Cs2Event(source = component.hash, sourceSlot = slot, optionName = component.opBase)
        dispatch(component, InterfaceEvent.DESELECT, event)
        dispatch(component, InterfaceEvent.TARGET_LEAVE, event)
    }

    // ------------------------------------------------------ transmit triggers

    /** A varp or varbit changed; fires the varp transmit hooks that listen for it. */
    fun varpChanged(id: Int) = transmit(InterfaceEvent.VARP_TRANSMIT, id)

    /** A skill changed; fires the stat transmit hooks that listen for it. */
    fun statChanged(id: Int) = transmit(InterfaceEvent.STAT_TRANSMIT, id)

    /** An item container changed; fires the inventory transmit hooks. */
    fun invChanged(id: Int) = transmit(InterfaceEvent.INV_TRANSMIT, id)

    /** A client int var changed; fires the varc transmit hooks. */
    fun varcChanged(id: Int) = transmit(InterfaceEvent.VARC_TRANSMIT, id)

    /** A client string var changed; fires the varc string transmit hooks. */
    fun varcStringChanged(id: Int) = transmit(InterfaceEvent.VARC_STRING_TRANSMIT, id)

    /**
     * Fires [event] on every component listening for [id].
     *
     * A script that rebound the hook supplies its own trigger list, which takes
     * priority over the cache's filter for the same reason the hook itself does.
     * A hook with no filter at all fires unconditionally - the client does the
     * same whenever it cannot match a change against a filter.
     */
    private fun transmit(event: InterfaceEvent, id: Int) {
        if (!liveMode) {
            return
        }
        val instance = instances() ?: return
        // A transmit hook that writes the very var it listens for would otherwise
        // re-enter here from inside its own dispatch and never come back. The client
        // cannot hit this because it queues transmit hooks and drains the queue once
        // per cycle; this runtime dispatches them straight off the write, so the
        // recursion has to be bounded here instead.
        if (transmitDepth >= MAX_TRANSMIT_DEPTH) {
            host.onError("Transmit hooks nested more than $MAX_TRANSMIT_DEPTH deep on ${event.boundKey} $id")
            return
        }
        transmitDepth++
        try {
            for (component in instance.components) {
                val bound = host.boundHooks[component.hash]?.get(event.boundKey)
                val ids = if (bound != null) {
                    bound.triggers.takeIf { it.isNotEmpty() }?.toIntArray()
                } else {
                    event.transmitFilter?.invoke(component.definition)
                }
                if (ids != null && !ids.contains(id)) {
                    continue
                }
                dispatch(component, event, Cs2Event(source = component.hash))
            }
        } finally {
            transmitDepth--
        }
    }

    // --------------------------------------------------------------- dispatch

    /**
     * Runs [component]'s handler for [event], preferring a hook a script bound at
     * runtime over the one the cache defined. Returns true when a hook ran.
     *
     * Public so the editor can fire an event by hand - a "run this hook" button in
     * the inspector goes through exactly the path a real interaction would,
     * including pointing the host's active-component register at the component
     * first so a `CC_*` script finds what it expects.
     */
    fun dispatch(component: ComponentState, event: InterfaceEvent, data: Cs2Event): Boolean {
        if (!liveMode) {
            return false
        }
        val bound = host.boundHooks[component.hash]?.get(event.boundKey)
        val defined = if (bound == null) event.definitionHook(component.definition) else null
        if (bound == null && defined == null) {
            return false
        }
        host.setActive(0, component)
        if (bound != null) {
            val error = capturingErrors { dispatcher.dispatch(bound, data) }
            record(event, component, bound.scriptId, true, error)
        } else {
            val error = capturingErrors { dispatcher.dispatch(defined!!, data) }
            record(event, component, defined!!.script, false, error)
        }
        return true
    }

    /** The pointer position relative to [component], packaged for a hook. */
    private fun pointerEvent(component: ComponentState, pointer: InterfacePointer) = Cs2Event(
        mouseX = pointer.x - component.screenX,
        mouseY = pointer.y - component.screenY,
        source = component.hash,
        sourceSlot = slotOf(component, pointer.x, pointer.y),
        optionName = component.opBase,
    )

    /**
     * The `event_comsubid` a hook fired on [component] carries.
     *
     * The client answers `hook.source.slotId` and nothing else, so a component
     * `CC_CREATE` made reports the slot it was created in. That is how a dropdown
     * knows which row the pointer is on: the row's hover hook passes its own slot
     * to `CC_FIND`, which is the only way back to the component from the script.
     *
     * [slotResolver] remains the answer for cache components, which have no slot of
     * their own - the editor configures a grid on them by hand so that hooks over an
     * item container still carry a plausible cell index.
     */
    private fun slotOf(component: ComponentState, pointX: Int, pointY: Int): Int {
        if (component.slotId >= 0) {
            return component.slotId
        }
        return slotResolver(component, pointX - component.screenX, pointY - component.screenY)
    }

    /**
     * Runs [block] and reports whatever the virtual machine logged while it ran.
     * Errors reach the host rather than the caller, so the only way to attribute
     * one to a dispatch is to watch the log grow around it.
     */
    private fun capturingErrors(block: () -> Unit): String? {
        val before = host.log.size
        block()
        if (host.log.size <= before) {
            return null
        }
        return host.log.subList(before, host.log.size).joinToString("; ")
    }

    private fun record(
        event: InterfaceEvent,
        component: ComponentState,
        script: Int,
        bound: Boolean,
        error: String?,
    ) {
        traceLog.add(InteractionTrace(event, component.index, component.hash, script, bound, error))
        while (traceLog.size > traceCapacity.coerceAtLeast(1)) {
            traceLog.removeAt(0)
        }
    }

    companion object {
        /**
         * The client's cycle, in milliseconds. Everything that repeats - hold,
         * click-repeat, hover-over, timers, the drag dead time - is counted in
         * these rather than in frames.
         */
        const val CYCLE_MILLIS = 20L

        /** Cycles a single [update] will catch up on after a stall. */
        const val MAX_CATCH_UP_CYCLES = 4

        /** Pixels a wheel notch scrolls when [directScroll] is on. */
        const val DEFAULT_SCROLL_STEP = 30

        /** Dispatches [trace] keeps by default. */
        const val DEFAULT_TRACE_CAPACITY = 256

        /**
         * How deeply a var write may set off another var write's transmit hooks
         * before the chain is abandoned. Matches the client's own guard on nested
         * hook requests.
         */
        const val MAX_TRANSMIT_DEPTH = 10

        /** Label the client uses when a continue option has no text of its own. */
        const val CONTINUE_TEXT = "Continue"

        /**
         * Highest option number a left click can invoke. Options 1-5 are filed under
         * the client's `clickType` 57, which `processMenuInput` promotes to the head of
         * the menu; 6-10 are 1007 and are right-click only. See [defaultOption].
         */
        const val LEFT_CLICK_OPTIONS = 5
    }
}

/**
 * Translation between GLFW input and the client's own key numbering.
 *
 * A component's key bindings are stored as client key codes, which are neither
 * ASCII nor any windowing toolkit's numbering - they are the values the client's
 * `CanvasKeyRecorder.REMAPPED_KEYS` table maps AWT virtual key codes onto. The
 * table below is that mapping re-keyed to GLFW, so a binding decoded from the
 * cache matches the key the editor actually saw.
 *
 * The numeric keypad is deliberately absent. The client marks keypad codes with
 * bit 7 and drops any code carrying it on key-down, so keypad presses never reach
 * a component in the first place.
 */
object InterfaceKeys {

    /** Client key code for Shift. */
    const val SHIFT = 81

    /** Client key code for Ctrl. */
    const val CTRL = 82

    /** Client key code for Alt. */
    const val ALT = 86

    /** Modifier mask bit meaning "Ctrl must be held". */
    const val MOD_CTRL = 0x1

    /** Modifier mask bit meaning "Alt must be held". */
    const val MOD_ALT = 0x2

    /** Modifier mask bit meaning "Shift must be held". */
    const val MOD_SHIFT = 0x4

    /** Modifier mask bit meaning "no modifier may be held". */
    const val MOD_NONE = 0x8

    private val CODES: Map<Int, Int> = buildMap {
        put(GLFW.GLFW_KEY_F1, 1)
        put(GLFW.GLFW_KEY_F2, 2)
        put(GLFW.GLFW_KEY_F3, 3)
        put(GLFW.GLFW_KEY_F4, 4)
        put(GLFW.GLFW_KEY_F5, 5)
        put(GLFW.GLFW_KEY_F6, 6)
        put(GLFW.GLFW_KEY_F7, 7)
        put(GLFW.GLFW_KEY_F8, 8)
        put(GLFW.GLFW_KEY_F9, 9)
        put(GLFW.GLFW_KEY_F10, 10)
        put(GLFW.GLFW_KEY_F11, 11)
        put(GLFW.GLFW_KEY_F12, 12)
        put(GLFW.GLFW_KEY_ESCAPE, 13)

        put(GLFW.GLFW_KEY_1, 16)
        put(GLFW.GLFW_KEY_2, 17)
        put(GLFW.GLFW_KEY_3, 18)
        put(GLFW.GLFW_KEY_4, 19)
        put(GLFW.GLFW_KEY_5, 20)
        put(GLFW.GLFW_KEY_6, 21)
        put(GLFW.GLFW_KEY_7, 22)
        put(GLFW.GLFW_KEY_8, 23)
        put(GLFW.GLFW_KEY_9, 24)
        put(GLFW.GLFW_KEY_0, 25)

        put(GLFW.GLFW_KEY_Q, 32)
        put(GLFW.GLFW_KEY_W, 33)
        put(GLFW.GLFW_KEY_E, 34)
        put(GLFW.GLFW_KEY_R, 35)
        put(GLFW.GLFW_KEY_T, 36)
        put(GLFW.GLFW_KEY_Y, 37)
        put(GLFW.GLFW_KEY_U, 38)
        put(GLFW.GLFW_KEY_I, 39)
        put(GLFW.GLFW_KEY_O, 40)
        put(GLFW.GLFW_KEY_P, 41)
        put(GLFW.GLFW_KEY_A, 48)
        put(GLFW.GLFW_KEY_S, 49)
        put(GLFW.GLFW_KEY_D, 50)
        put(GLFW.GLFW_KEY_F, 51)
        put(GLFW.GLFW_KEY_G, 52)
        put(GLFW.GLFW_KEY_H, 53)
        put(GLFW.GLFW_KEY_J, 54)
        put(GLFW.GLFW_KEY_K, 55)
        put(GLFW.GLFW_KEY_L, 56)
        put(GLFW.GLFW_KEY_Z, 64)
        put(GLFW.GLFW_KEY_X, 65)
        put(GLFW.GLFW_KEY_C, 66)
        put(GLFW.GLFW_KEY_V, 67)
        put(GLFW.GLFW_KEY_B, 68)
        put(GLFW.GLFW_KEY_N, 69)
        put(GLFW.GLFW_KEY_M, 70)

        put(GLFW.GLFW_KEY_TAB, 80)
        put(GLFW.GLFW_KEY_LEFT_SHIFT, SHIFT)
        put(GLFW.GLFW_KEY_RIGHT_SHIFT, SHIFT)
        put(GLFW.GLFW_KEY_LEFT_CONTROL, CTRL)
        put(GLFW.GLFW_KEY_RIGHT_CONTROL, CTRL)
        put(GLFW.GLFW_KEY_SPACE, 83)
        put(GLFW.GLFW_KEY_ENTER, 84)
        put(GLFW.GLFW_KEY_KP_ENTER, 84)
        put(GLFW.GLFW_KEY_BACKSPACE, 85)
        put(GLFW.GLFW_KEY_LEFT_ALT, ALT)
        put(GLFW.GLFW_KEY_RIGHT_ALT, ALT)

        put(GLFW.GLFW_KEY_KP_ADD, 87)
        put(GLFW.GLFW_KEY_KP_SUBTRACT, 88)
        put(GLFW.GLFW_KEY_KP_MULTIPLY, 89)
        put(GLFW.GLFW_KEY_KP_DIVIDE, 90)

        put(GLFW.GLFW_KEY_LEFT, 96)
        put(GLFW.GLFW_KEY_RIGHT, 97)
        put(GLFW.GLFW_KEY_UP, 98)
        put(GLFW.GLFW_KEY_DOWN, 99)
        put(GLFW.GLFW_KEY_INSERT, 100)
        put(GLFW.GLFW_KEY_DELETE, 101)
        put(GLFW.GLFW_KEY_HOME, 102)
        put(GLFW.GLFW_KEY_END, 103)
        put(GLFW.GLFW_KEY_PAGE_UP, 104)
        put(GLFW.GLFW_KEY_PAGE_DOWN, 105)
    }

    /** The client key code for a GLFW key, or 0 when the client ignores that key. */
    fun fromGlfw(code: Int): Int = CODES[code] ?: 0

    /**
     * Turns a GLFW modifier mask into the client's, whose bits are ordered
     * differently and which spells "no modifier" as a bit of its own.
     */
    fun modifierMask(glfwModifiers: Int): Int {
        var mask = 0
        if ((glfwModifiers and GLFW.GLFW_MOD_CONTROL) != 0) {
            mask = mask or MOD_CTRL
        }
        if ((glfwModifiers and GLFW.GLFW_MOD_ALT) != 0) {
            mask = mask or MOD_ALT
        }
        if ((glfwModifiers and GLFW.GLFW_MOD_SHIFT) != 0) {
            mask = mask or MOD_SHIFT
        }
        return if (mask == 0) MOD_NONE else mask
    }
}
