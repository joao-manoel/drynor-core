package org.darkan.tools.rsinterface

import org.darkan.tools.engine.render.ObjIconStack
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

/**
 * Mutable render state for a single interface component.
 *
 * This is the seam between the immutable cache definition and everything that
 * mutates a live interface - the editor's property panel today, a CS2
 * interpreter tomorrow. [InterfaceRenderer] reads *only* from here and never
 * from [definition]; the definition is kept purely as the reset baseline and as
 * a source of the fields nothing can change (type, parent, hook scripts).
 *
 * ### Driving this from CS2
 *
 * Resolve a component with [InterfaceInstance.state] (component index) or
 * [InterfaceInstance.stateByHash] (the packed `interfaceId shl 16 or index`
 * hash that CS2 and the network protocol pass around), then:
 *
 * - **Single-field setters assign the property directly.** `IF_SETHIDE` is
 *   `state.hidden = value`, `IF_SETTEXT` is `state.text = value`,
 *   `IF_SETCOLOUR` is `state.colour = rgb`, `IF_SETTRANS` is
 *   `state.transparency = value`, `IF_SETGRAPHIC` is `state.graphicId = id`,
 *   and so on. Nothing else needs to be called.
 * - **The methods below exist for the ops that move more than one field, or
 *   that change the box model.** Everything that feeds the layout pass
 *   ([basePositionX], [baseWidth], the position/size modes, the scroll fields)
 *   has a private setter precisely so those go through a method that marks the
 *   interface dirty - an interpreter can never forget to trigger a relayout.
 *
 * | Opcode | Call |
 * |---|---|
 * | `IF_SETPOSITION` | [setPosition] |
 * | `IF_SETSIZE` | [setSize] |
 * | `IF_SETASPECT` | [setAspect] |
 * | `IF_SETSCROLLPOS` | [setScrollPos] |
 * | `IF_SETSCROLLSIZE` | [setScrollSize] |
 * | `IF_SETMODEL` | [setModel] |
 * | `IF_SETMODELANGLE` | [setModelAngle] |
 * | `IF_SETOBJECT` | [setObject] |
 * | `IF_SETTEXTALIGN` | [setTextAlign] |
 * | `IF_SETOP` | [setOption] |
 * | `IF_CLEAROPS` | [clearOptions] |
 * | `IF_SETOPKEY` / `IF_SETOPTKEY` | [setOptionKey] |
 * | `IF_SETOPCURSOR` | [setOptionCursor] |
 * | `IF_SETDRAGDEADZONE` / `IF_SETDRAGDEADTIME` | [dragDeadzone] / [dragDeadTime] |
 *
 * ### Interaction state
 *
 * The block starting at [events] is everything the interaction runtime needs and
 * the renderer never touches. It is seeded from the definition just like the draw
 * state, because CS2 rewrites options, cursors and the events bitmask at runtime
 * and the editor has to be able to put it all back.
 *
 * Several of these fields correct names the cache decoder guessed at. The
 * mapping was recovered from the client's `Component.decode`, and the comment on
 * each field records which client field it really is - trust the comment over the
 * decoder's field name.
 */
class ComponentState(
    /** The immutable cache definition this state was seeded from. */
    val definition: InterfaceComponentDefinition,
    /** Index of this component within its interface's component array. */
    val index: Int,
    /** Id of the interface that owns this component. */
    val interfaceId: Int,
    /**
     * Slot this component occupies among its parent's runtime-created children,
     * or -1 when it came from the cache. The client's `Component.slotId`.
     *
     * `CC_CREATE` builds children the cache knows nothing about - a dropdown's
     * nine-slice border, a scrollbar's parts, an inventory's item cells - and
     * addresses them by slot rather than by component index. This is that slot,
     * and it is also the `event_comsubid` a hook fired on such a child carries.
     */
    val slotId: Int = -1
) {
    /** Packed component hash used by CS2 and the network protocol. */
    val hash: Int = (interfaceId shl 16) or index

    /**
     * True when `CC_CREATE` made this component rather than the cache.
     *
     * A runtime component is not backed by anything on disk: its definition is a
     * blank carrying only the type and parent, it is never encoded when the
     * interface is saved, and it disappears on [InterfaceInstance.reset].
     */
    val runtime: Boolean get() = slotId >= 0

    /** Invoked whenever a change invalidates the computed box model. */
    internal var invalidateLayout: () -> Unit = {}

    // --- Box model inputs (mutated through the methods at the bottom) ------

    var basePositionX: Int = definition.basePositionX
        private set
    var basePositionY: Int = definition.basePositionY
        private set
    var baseWidth: Int = definition.baseWidth
        private set
    var baseHeight: Int = definition.baseHeight
        private set
    var horizontalSizeMode: Int = definition.horizontalSizeMode.toInt()
        private set
    var verticalSizeMode: Int = definition.verticalSizeMode.toInt()
        private set
    var horizontalPositionMode: Int = definition.horizontalPositionMode.toInt()
        private set
    var verticalPositionMode: Int = definition.verticalPositionMode.toInt()
        private set

    /**
     * Aspect ratio numerator/denominator used by size modes of 4. The cache
     * never encodes these; the client defaults them to 1:1 and only CS2 changes
     * them, so they live here rather than on the definition.
     */
    var aspectX: Int = 1
        private set
    var aspectY: Int = 1
        private set

    var scrollWidth: Int = definition.scrollWidth
        private set
    var scrollHeight: Int = definition.scrollHeight
        private set
    var scrollX: Int = 0
        internal set
    var scrollY: Int = 0
        internal set

    // --- Common draw state ------------------------------------------------

    var hidden: Boolean = definition.hidden

    /** RGB colour, 0xRRGGBB. Alpha comes from [transparency], not from here. */
    var colour: Int = definition.colour

    /** 0 = fully opaque, 255 = fully transparent (the client's convention). */
    var transparency: Int = definition.alpha

    // --- Rectangle (type 3) -----------------------------------------------

    var filled: Boolean = definition.filled

    // --- Text (type 4) ----------------------------------------------------

    var text: String = definition.text
    var fontId: Int = definition.fontId
    var horizontalTextAlign: Int = definition.horizontalTextAlign
    var verticalTextAlign: Int = definition.verticalTextAlign
    var lineSpacing: Int = definition.lineHeight
    var maxTextLines: Int = definition.lineCount
    var shadow: Boolean = definition.shaded

    // --- Graphic (type 5) -------------------------------------------------

    var graphicId: Int = definition.defaultImage

    /** 2D rotation; the cache stores 65536 units per full turn. */
    var graphicAngle: Int = definition.imageRotation

    /** The cache's graphic flag bit 0: repeat the graphic instead of stretching. */
    var tiling: Boolean = definition.aBoolean4861
    var flipVertical: Boolean = definition.flipVertical
    var flipHorizontal: Boolean = definition.flipHorizontal

    /** Outline thickness added around the graphic by the client's `draw`. */
    var borderThickness: Int = definition.rotation

    /** Drop-shadow colour baked into the graphic by the client's `draw`. */
    var graphicShadow: Int = definition.backgroundColour

    // --- Model (type 6) ---------------------------------------------------

    var mediaType: Int = definition.defaultMediaType
    var mediaId: Int = definition.defaultMediaId
    var animation: Int = definition.animation
    var modelOriginX: Int = definition.viewportX
    var modelOriginY: Int = definition.viewportY
    var modelOriginZ: Int = definition.viewportZ
    var modelPitch: Int = definition.graphicPitch
    var modelRoll: Int = definition.graphicRoll
    var modelYaw: Int = definition.graphicYaw
    var modelScale: Int = definition.graphicScale

    /** Model viewport aspect, the client's `aspectWidth` / `aspectHeight`. */
    var modelAspectWidth: Int = definition.viewportWidth
    var modelAspectHeight: Int = definition.viewportHeight

    // --- Line (type 9) ----------------------------------------------------

    var lineWidth: Int = definition.lineWidth

    /** When set the line runs bottom-left to top-right instead of top-left down. */
    var lineMirrored: Boolean = definition.lineMirrored

    // --- Item slot --------------------------------------------------------

    /**
     * Item occupying this component, or -1. Graphic, text and model components
     * all change behaviour when an item is set: graphics and models draw the
     * item's icon, text draws the item's name.
     */
    var itemId: Int = -1
    var itemAmount: Int = 0

    var stackNumbers: ObjIconStack = ObjIconStack.WHEN_MULTIPLE

    // --- Interaction metadata ---------------------------------------------

    /**
     * The component's packed events bitmask - the client's `IFEvents.settingsHash`.
     * Every interaction permission lives in here rather than in a boolean, and the
     * server rewrites the whole word at once, so it is exposed as one int with the
     * individual bits read through the accessors below.
     *
     * | Bit(s) | Meaning |
     * |---|---|
     * | 0 | the "Continue" option is enabled |
     * | 1-10 | click options 1-10 enabled |
     * | 11-17 | use-option flags (what this component may be used on) |
     * | 18-20 | drag depth: how far up the parent chain the drag is confined |
     * | 21 | may be dropped onto |
     * | 22 | may be targeted by a use |
     * | 23 | drag is confined to the whole frame rather than a parent |
     */
    var events: Int = definition.setting.setting

    /**
     * Right-click options, indexed 0-9 for options 1-10. Sparse: CS2 fills in
     * individual slots with `IF_SETOP`, so entries may be null or blank and both
     * mean "no option here".
     */
    var options: Array<String?> = optionArray(definition.options)
        private set

    /**
     * The target name appended to every option ("Wield -> *Rune platebody*").
     * The decoder calls this `name`; the client calls it `opBase`, and it is what
     * the `event_opbase` hook argument substitutes to.
     */
    var opBase: String = definition.name

    /**
     * Label for the bit-0 "Continue" option, or null for the default. The decoder
     * calls this `optionOverride`; it is the client's `resumeText`, and it
     * overrides nothing else.
     */
    var continueText: String? = definition.optionOverride

    /**
     * Verb shown while this component is selected for use ("Use", "Cast"). The
     * client's `targetVerb`; only meaningful when [useOptionFlags] is non-zero.
     */
    var useOption: String = definition.useOption

    /**
     * Blocks interaction reaching anything drawn behind this component. The
     * decoder calls this `disableHover`, but the client field it decodes is
     * `preventClickThrough`: a component with it set *swallows* the pointer, it
     * does not pass it through. Set by `IF_SETNOCLICKTHROUGH`.
     */
    var noClickThrough: Boolean = definition.disableHover

    /**
     * True when the component has keyboard option bindings. The client's
     * `hasInteraction`; the decoder sets it from the decoded key bindings and
     * names it `clickable`, which it is not.
     */
    var clickable: Boolean = definition.clickable

    /** Per-option cursor ids, indexed like [options]; -1 for the default cursor. */
    var optionCursors: IntArray = definition.mouseIcon?.copyOf() ?: IntArray(0)
        private set

    /** Cursor while merely hovering, the client's `moveOverCursor`; -1 for none. */
    var hoverCursor: Int = -1

    /** Cursor while this component is selected for use; the client's `targetOverCursor`. */
    var targetCursor: Int = definition.anInt4698

    /** Cursor over invalid use targets; the client's `targetLeaveCursor`. */
    var targetLeaveCursor: Int = definition.anInt4839

    /**
     * How far the pointer must travel, in pixels, before a press becomes a drag.
     * The decoder calls this `anInt4708` and guesses "drag type"; it is the
     * client's `dragDeadzone`. Set by `IF_SETDRAGDEADZONE`.
     */
    var dragDeadzone: Int = definition.anInt4708

    /**
     * How many client cycles a press must be held before it can become a drag.
     * The decoder calls this `anInt4795` and guesses "drag slider"; it is the
     * client's `dragDeadTime`. Set by `IF_SETDRAGDEADTIME`.
     */
    var dragDeadTime: Int = definition.anInt4795

    /** The client's `dragType`; `anInt4860` in the decoder. */
    var dragType: Int = definition.anInt4860

    /**
     * Component a drag of this one is confined to, or null.
     *
     * This is the client's `depthChildOverride`, set by opcode 68/899 - which Jagex
     * names `CC_SETDRAGGABLE` / `IF_SETDRAGGABLE` but the client calls
     * `SETDEPTHCHILD`. It does two things:
     *
     * - it makes the component [draggable] at all, which is the only route a
     *   `CC_CREATE` component has: the drag depth lives in the [events] bitmask and
     *   nothing in this revision can write that at runtime. A scrollbar thumb is
     *   draggable purely because its builder script sets this.
     * - it supplies the rectangle the drag is clamped inside, and the origin the
     *   drag hook's coordinates are measured from. For a scrollbar thumb it is the
     *   track, which is exactly why the hook can hand the script a position it can
     *   turn straight into a scroll offset.
     */
    var depthChild: ComponentState? = null
        internal set

    /**
     * Keyboard bindings for options 1-10 (indices 0-9) plus "select for use"
     * (index 10), matching the client's eleven-entry arrays.
     *
     * The decoder's three key arrays are rotated relative to their names, which
     * the client's decode order settles:
     * - [optionKeyCodes] is the client key code, from `keyRepeats`
     * - [optionKeyModifiers] is the modifier mask, from `keyCodes`
     * - [optionKeyRepeats] is the re-trigger cycle count, from `keyModifiers`
     *
     * The modifier mask is 1 = Ctrl, 2 = Alt, 4 = Shift, 8 = no modifier held.
     */
    var optionKeyCodes: IntArray = keyArray(definition.keyRepeats)
        private set
    var optionKeyModifiers: IntArray = keyArray(definition.keyCodes)
        private set
    var optionKeyRepeats: IntArray = definition.keyModifiers?.copyOf(OPTION_KEYS) ?: IntArray(OPTION_KEYS)
        private set

    /**
     * Typed characters bound to options 1-10, the client's `keyCharCodes`. Never
     * decoded from the cache - only `IF_SETOPTKEY` sets these - so it starts empty.
     */
    var optionKeyChars: IntArray = IntArray(OPTION_KEYS)
        private set

    /**
     * Cycle each option's keyboard binding may next fire on, the client's
     * `cycles`. Zero means ready, [Int.MAX_VALUE] means "not until the key is
     * released" - the client's way of making a binding one-shot.
     */
    val optionKeyCooldowns: IntArray = IntArray(OPTION_KEYS)

    // --- Item slot grid ---------------------------------------------------

    /**
     * Geometry of the item-slot grid used to turn a pointer position into the
     * `sourceSlot`/`targetSlot` a hook is passed.
     *
     * The cache never describes this: the client builds one child component per
     * slot at runtime from CS2, and the slot index is that child's `slotId`. With
     * no dynamic components there is nothing to read the slot off, so the editor
     * supplies the grid through [setSlotGrid] and [slotAt] does the arithmetic.
     * Zero columns or rows means "not a slot grid" and every slot resolves to -1.
     */
    var slotColumns: Int = 0
        private set
    var slotRows: Int = 0
        private set
    var slotWidth: Int = 0
        private set
    var slotHeight: Int = 0
        private set
    var slotPaddingX: Int = 0
        private set
    var slotPaddingY: Int = 0
        private set

    // --- Live interaction state -------------------------------------------

    /**
     * True between press and release, the client's `isPressed`. Lives here rather
     * than in the interaction runtime because the client keeps it on the
     * component and the renderer wants it for pressed-button art.
     */
    var pressed: Boolean = false
        internal set

    /** True while the pointer is inside this component, the client's `isHooked`. */
    var hovered: Boolean = false
        internal set

    /**
     * True once a script has bound any callback to this component at runtime.
     *
     * [definition] answers `hasScript` for hooks the *cache* declared, and that is
     * all a component built by `CC_CREATE` would ever have to go on - its
     * definition is blank. A dropdown row or a scrollbar arrow gets every one of its
     * callbacks from `CC_SETON*` at runtime, so without this it would look inert to
     * [interactive] no matter how many hooks were attached to it.
     *
     * Kept as a flag the host raises rather than a lookup into the host's hook map,
     * because [interactive] is read for every component under the pointer on every
     * frame.
     */
    var scripted: Boolean = false
        internal set

    /**
     * True when an `IF_SETONOP` hook is attached, from the cache or from a script.
     *
     * The client keeps one field, `Component.params`, that both the cache decode and
     * `IF_SETONOP` write; the decoder only ever sees the cache half, so this rejoins
     * them. It matters because that field is what makes an option *offerable*: see
     * [optionAt], where a disabled option is still shown when an op hook exists.
     *
     * A `CC_CREATE` component has no cache half at all, so for a dropdown row - whose
     * "Select" option is named and hooked entirely by the script that opens the list -
     * this flag is the only evidence the option exists.
     */
    var optionHook: Boolean = definition.params != null
        internal set

    // --- Computed box model (output of the layout pass) --------------------

    /** Position relative to the parent's content origin, in pixels. */
    var x: Int = 0
        internal set
    var y: Int = 0
        internal set
    var width: Int = definition.baseWidth
        internal set
    var height: Int = definition.baseHeight
        internal set

    /** Absolute screen position, valid after the last draw pass. */
    var screenX: Int = 0
        internal set
    var screenY: Int = 0
        internal set

    /** The component's type; never mutable, so it is read straight through. */
    val type: Int get() = definition.type

    /** Index of the parent component within the same interface, or -1. */
    val parent: Int get() = definition.parent

    /** Content type, which gives some components hard-coded client behaviour. */
    val contentType: Int get() = definition.contentType

    // --- Events bitmask accessors -----------------------------------------

    /** True when option [index] (0-based, so option `index + 1`) may be clicked. */
    fun optionEnabled(index: Int): Boolean = ((events shr (index + 1)) and 0x1) != 0

    /** True when the bit-0 "Continue" option is enabled. */
    val continueEnabled: Boolean get() = (events and 0x1) != 0

    /** Which entity classes this component may be used on; 0 means it cannot. */
    val useOptionFlags: Int get() = (events shr 11) and 0x7f

    /** How many parents up a drag of this component is confined; 0 means no drag. */
    val dragDepth: Int get() = (events shr 18) and 0x7

    /** True when another component may be dropped onto this one. */
    val dropTarget: Boolean get() = ((events shr 21) and 0x1) != 0

    /** True when this component may be the target of a use. */
    val useTarget: Boolean get() = ((events shr 22) and 0x1) != 0

    /** True when a drag of this component is confined to the whole frame. */
    val dragUnbounded: Boolean get() = ((events shr 23) and 0x1) != 0

    /**
     * True when this component can be dragged at all, the client's `hasDepthFlags`:
     * either the events bitmask grants a drag depth, or a script named a
     * [depthChild] to confine the drag to.
     */
    val draggable: Boolean get() = dragDepth > 0 || dragUnbounded || depthChild != null

    /**
     * True when the interaction runtime should consider this component at all.
     *
     * Mirrors the client's outer gate in `processInterfaceCycle`: a component with
     * no hooks, no events and no content type is drawn and nothing more, which is
     * what stops decorative art from eating clicks meant for the button under it.
     * Containers always qualify because they carry their children's coordinates.
     */
    val interactive: Boolean
        get() = type == ComponentType.CONTAINER ||
            events != 0 ||
            contentType != 0 ||
            // The decoder raises this for any component that decoded a hook, so it
            // already stands in for "has a cache hook of any kind".
            definition.hasScript ||
            // ...and this stands in for the same thing when the hook was bound by a
            // script instead, which is the only kind a runtime component ever has.
            scripted

    /**
     * Label the client would show for option [index] (0-based), or null when the
     * option is absent or disabled. Transcribes `Component.componentMenuOption`:
     * a disabled option is still offered when the component has an `IF_SETONOP`
     * hook, because that hook is the only handler such options ever had.
     */
    fun optionAt(index: Int): String? {
        if (!optionEnabled(index) && !optionHook) {
            return null
        }
        val label = options.getOrNull(index) ?: return null
        return label.takeIf { it.isNotBlank() }
    }

    /** Cursor id for option [index], or -1; the client's `Component.getCursor`. */
    fun optionCursor(index: Int): Int {
        if (!optionEnabled(index) && !optionHook) {
            return -1
        }
        return optionCursors.getOrElse(index) { -1 }
    }

    /**
     * Slot index at ([localX], [localY]), measured from this component's top-left
     * corner, or -1 when no slot grid is configured or the point falls in the
     * padding between slots.
     */
    fun slotAt(localX: Int, localY: Int): Int {
        if (slotColumns <= 0 || slotRows <= 0 || slotWidth <= 0 || slotHeight <= 0) {
            return -1
        }
        val strideX = slotWidth + slotPaddingX
        val strideY = slotHeight + slotPaddingY
        if (localX < 0 || localY < 0) {
            return -1
        }
        val column = localX / strideX
        val row = localY / strideY
        if (column >= slotColumns || row >= slotRows) {
            return -1
        }
        if (localX - (column * strideX) >= slotWidth || localY - (row * strideY) >= slotHeight) {
            return -1
        }
        return (row * slotColumns) + column
    }

    // --- Layout mutators --------------------------------------------------

    fun setPosition(x: Int, y: Int, xMode: Int = horizontalPositionMode, yMode: Int = verticalPositionMode) {
        basePositionX = x
        basePositionY = y
        horizontalPositionMode = xMode
        verticalPositionMode = yMode
        invalidateLayout()
    }

    fun setSize(width: Int, height: Int, widthMode: Int = horizontalSizeMode, heightMode: Int = verticalSizeMode) {
        baseWidth = width
        baseHeight = height
        horizontalSizeMode = widthMode
        verticalSizeMode = heightMode
        invalidateLayout()
    }

    fun setAspect(aspectX: Int, aspectY: Int) {
        this.aspectX = aspectX.coerceAtLeast(1)
        this.aspectY = aspectY.coerceAtLeast(1)
        invalidateLayout()
    }

    fun setScrollPos(scrollX: Int, scrollY: Int) {
        this.scrollX = scrollX
        this.scrollY = scrollY
        invalidateLayout()
    }

    fun setScrollSize(scrollWidth: Int, scrollHeight: Int) {
        this.scrollWidth = scrollWidth
        this.scrollHeight = scrollHeight
        invalidateLayout()
    }

    // --- Multi-field mutators ---------------------------------------------

    /** `IF_SETMODEL`: points the component at a raw cache model. */
    fun setModel(modelId: Int) {
        mediaType = MediaType.RAW_MODEL
        mediaId = modelId
    }

    fun setModelAngle(pitch: Int, roll: Int, yaw: Int, scale: Int) {
        modelPitch = pitch
        modelRoll = roll
        modelYaw = yaw
        modelScale = scale
    }

    /** `IF_SETOBJECT`: puts an item in the component; -1 clears it. */
    fun setObject(itemId: Int, amount: Int, stackNumbers: ObjIconStack = ObjIconStack.WHEN_MULTIPLE) {
        this.itemId = itemId
        this.itemAmount = amount
        this.stackNumbers = stackNumbers
    }

    fun setTextAlign(horizontal: Int, vertical: Int, lineSpacing: Int) {
        horizontalTextAlign = horizontal
        verticalTextAlign = vertical
        this.lineSpacing = lineSpacing
    }

    // --- Interaction mutators ---------------------------------------------

    /**
     * `IF_SETOP`: names option [index] (0-based). Grows [options] to fit, which is
     * what the client's `addOptionName` does - scripts routinely set option 5 on a
     * component the cache gave two options.
     */
    fun setOption(index: Int, label: String) {
        if (index < 0 || index >= OPTION_COUNT) {
            return
        }
        if (options.size <= index) {
            options = options.copyOf(index + 1)
        }
        options[index] = label
    }

    /** `IF_CLEAROPS`: drops every option, as the client's `options = null` does. */
    fun clearOptions() {
        options = arrayOfNulls(0)
    }

    /** `IF_SETOPCURSOR`: cursor shown while hovering option [index] (0-based). */
    fun setOptionCursor(index: Int, cursor: Int) {
        if (index < 0 || index >= OPTION_COUNT) {
            return
        }
        if (optionCursors.size <= index) {
            val grown = IntArray(index + 1) { -1 }
            optionCursors.copyInto(grown)
            optionCursors = grown
        }
        optionCursors[index] = cursor
    }

    /**
     * `IF_SETOPKEY` (indices 0-9) and `IF_SETOPTKEY` (index 10): binds a key to an
     * option. [repeat] is the cycle count before the binding may fire again; zero
     * makes it one-shot until the key is released, which is the client's default.
     *
     * Setting a code marks the component [clickable], matching the client's
     * `setTriggerKey` - that flag is what admits the component to key processing
     * at all.
     */
    fun setOptionKey(index: Int, code: Int, modifiers: Int, repeat: Int = 0) {
        if (index < 0 || index >= OPTION_KEYS) {
            return
        }
        optionKeyCodes[index] = code
        optionKeyModifiers[index] = modifiers
        optionKeyRepeats[index] = repeat
        optionKeyCooldowns[index] = 0
        refreshClickable()
    }

    /** `IF_SETOPTCHAR`: binds a typed character to option [index] (0-based). */
    fun setOptionKeyChar(index: Int, char: Int) {
        if (index < 0 || index >= OPTION_KEYS) {
            return
        }
        optionKeyChars[index] = char
        refreshClickable()
    }

    /**
     * Sets both halves of the drag threshold at once - the dead zone and dead time a
     * press must clear before it counts as a drag. The cache and CS2 only ever set
     * these one at a time (`IF_SETDRAGDEADZONE` / `IF_SETDRAGDEADTIME`); this exists
     * for the editor's property panel, which offers them as one control.
     */
    fun setDraggable(deadzone: Int, deadTime: Int) {
        dragDeadzone = deadzone
        dragDeadTime = deadTime
    }

    /**
     * Describes the item-slot grid this component lays its slots out on, so hooks
     * fired over it carry a real slot index instead of -1. See [slotColumns].
     */
    fun setSlotGrid(
        columns: Int,
        rows: Int,
        slotWidth: Int,
        slotHeight: Int,
        paddingX: Int = 0,
        paddingY: Int = 0
    ) {
        slotColumns = columns.coerceAtLeast(0)
        slotRows = rows.coerceAtLeast(0)
        this.slotWidth = slotWidth.coerceAtLeast(0)
        this.slotHeight = slotHeight.coerceAtLeast(0)
        slotPaddingX = paddingX.coerceAtLeast(0)
        slotPaddingY = paddingY.coerceAtLeast(0)
    }

    /** Recomputes [clickable] from the key bindings, as `setTriggerKey` does. */
    private fun refreshClickable() {
        clickable = optionKeyCodes.indices.any { optionKeyCodes[it] != 0 || optionKeyChars[it] > 0 }
    }

    /** Restores every field to the value decoded from the cache. */
    fun reset() {
        basePositionX = definition.basePositionX
        basePositionY = definition.basePositionY
        baseWidth = definition.baseWidth
        baseHeight = definition.baseHeight
        horizontalSizeMode = definition.horizontalSizeMode.toInt()
        verticalSizeMode = definition.verticalSizeMode.toInt()
        horizontalPositionMode = definition.horizontalPositionMode.toInt()
        verticalPositionMode = definition.verticalPositionMode.toInt()
        aspectX = 1
        aspectY = 1
        scrollWidth = definition.scrollWidth
        scrollHeight = definition.scrollHeight
        scrollX = 0
        scrollY = 0
        hidden = definition.hidden
        colour = definition.colour
        transparency = definition.alpha
        filled = definition.filled
        text = definition.text
        fontId = definition.fontId
        horizontalTextAlign = definition.horizontalTextAlign
        verticalTextAlign = definition.verticalTextAlign
        lineSpacing = definition.lineHeight
        maxTextLines = definition.lineCount
        shadow = definition.shaded
        graphicId = definition.defaultImage
        graphicAngle = definition.imageRotation
        tiling = definition.aBoolean4861
        flipVertical = definition.flipVertical
        flipHorizontal = definition.flipHorizontal
        borderThickness = definition.rotation
        graphicShadow = definition.backgroundColour
        mediaType = definition.defaultMediaType
        mediaId = definition.defaultMediaId
        animation = definition.animation
        modelOriginX = definition.viewportX
        modelOriginY = definition.viewportY
        modelOriginZ = definition.viewportZ
        modelPitch = definition.graphicPitch
        modelRoll = definition.graphicRoll
        modelYaw = definition.graphicYaw
        modelScale = definition.graphicScale
        modelAspectWidth = definition.viewportWidth
        modelAspectHeight = definition.viewportHeight
        lineWidth = definition.lineWidth
        lineMirrored = definition.lineMirrored
        itemId = -1
        itemAmount = 0
        stackNumbers = ObjIconStack.WHEN_MULTIPLE
        events = definition.setting.setting
        options = optionArray(definition.options)
        opBase = definition.name
        continueText = definition.optionOverride
        useOption = definition.useOption
        noClickThrough = definition.disableHover
        clickable = definition.clickable
        optionCursors = definition.mouseIcon?.copyOf() ?: IntArray(0)
        hoverCursor = -1
        targetCursor = definition.anInt4698
        targetLeaveCursor = definition.anInt4839
        dragDeadzone = definition.anInt4708
        dragDeadTime = definition.anInt4795
        dragType = definition.anInt4860
        depthChild = null
        optionKeyCodes = keyArray(definition.keyRepeats)
        optionKeyModifiers = keyArray(definition.keyCodes)
        optionKeyRepeats = definition.keyModifiers?.copyOf(OPTION_KEYS) ?: IntArray(OPTION_KEYS)
        optionKeyChars = IntArray(OPTION_KEYS)
        optionKeyCooldowns.fill(0)
        slotColumns = 0
        slotRows = 0
        slotWidth = 0
        slotHeight = 0
        slotPaddingX = 0
        slotPaddingY = 0
        pressed = false
        hovered = false
        scripted = false
        optionHook = definition.params != null
        invalidateLayout()
    }

    companion object {
        /** Right-click options a component can carry, options 1-10. */
        const val OPTION_COUNT = 10

        /**
         * Length of the client's keyboard binding arrays: ten option bindings plus
         * one for "select this component for use".
         */
        const val OPTION_KEYS = OPTION_COUNT + 1

        /** Copies the decoded options into a slot array scripts may grow and blank. */
        private fun optionArray(source: Array<String>?): Array<String?> =
            Array(source?.size ?: 0) { source?.get(it) }

        /** Widens one of the decoder's byte key arrays to the client's eleven slots. */
        private fun keyArray(source: ByteArray?): IntArray {
            val codes = IntArray(OPTION_KEYS)
            if (source != null) {
                for (index in 0 until minOf(source.size, OPTION_KEYS)) {
                    codes[index] = source[index].toInt()
                }
            }
            return codes
        }
    }
}
