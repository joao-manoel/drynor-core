package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import imgui.type.ImString
import org.darkan.cs2.vm.Cs2HookDispatcher
import org.darkan.tools.rsinterface.ComponentFields
import org.darkan.tools.rsinterface.ComponentState
import org.darkan.tools.rsinterface.ComponentType
import org.darkan.tools.rsinterface.InterfaceEvent
import org.darkan.tools.rsinterface.MediaType
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook

/**
 * The property sheet for the selected component.
 *
 * ### Authored versus resolved
 *
 * Every geometry row shows two numbers: the `basePositionX` an author typed and
 * the `x` / `screenX` the layout pass produced from it. They are usually the
 * same, and when they are not it is because a position or size *mode* did
 * something - inset from the parent, centred, proportional. The gap between the
 * two columns is the whole diagnosis of "why is this component not where I put
 * it", so the panel refuses to show one without the other, and the modes are
 * named rather than numbered because `verticalPositionMode = 5` tells nobody
 * anything.
 *
 * ### Why the widgets hold no state
 *
 * The obvious property sheet copies the component into a set of buffers when the
 * selection changes and writes them back on edit, and then has to work out when
 * to re-sync: after an undo, after a script wrote the field, after a structural
 * edit renumbered everything. Every one of those is a bug waiting to happen. The
 * fields here are built fresh from the definition each frame instead - ImGui
 * keeps its own buffer for whichever widget is being typed into, so feeding it
 * the model value every frame is harmless, and the panel can never show a stale
 * value. Text is the one exception: [ImString] owns a byte buffer that would be
 * overwritten mid-keystroke, so those are cached and re-seeded when the
 * selection or the document revision changes.
 *
 * ### Undo granularity
 *
 * Every write goes through [InterfaceEditContext] with a coalesce key naming the
 * field and the component, so a drag across a slider is one undo step rather
 * than sixty, and [ImGui.isItemDeactivatedAfterEdit] closes the step on release
 * so the next gesture starts a new one.
 */
class InterfaceInspectorPanel(
    private val context: InterfaceEditContext,
    private val assets: AssetPicker,
    /** Fires an event on a component through the interaction runtime. */
    private val onRunHook: (Int, InterfaceEvent) -> Unit,
    /** Exports and opens a clientscript's source through the reload panel. */
    private val onOpenScript: (Int) -> Unit,
) {
    var visible: Boolean = true

    /** Cached text buffers, re-seeded when the selection or the document changes. */
    private val strings = HashMap<String, ImString>()
    private var stringsFor = -1
    private var stringsRevision = -1

    private var showAdvanced = false

    fun render() {
        if (!visible) {
            return
        }
        if (!ImGui.begin("Inspector")) {
            ImGui.end()
            return
        }
        val definition = context.primaryDefinition()
        if (definition == null) {
            ImGuiText.disabled("Select a component.")
            ImGuiText.disabled("The tree, the stage and the validation list all select.")
            ImGui.end()
            return
        }
        if (stringsFor != context.primary || stringsRevision != context.revision) {
            strings.clear()
            stringsFor = context.primary
            stringsRevision = context.revision
        }

        val index = context.primary
        val state = context.primaryState()
        header(index, definition, state)

        if (ImGui.collapsingHeader("Layout", ImGuiTreeNodeFlags.DefaultOpen)) {
            layout(index, definition, state)
        }
        if (ImGui.collapsingHeader("Appearance", ImGuiTreeNodeFlags.DefaultOpen)) {
            appearance(index, definition)
        }
        val typeName = ComponentType.name(definition.type)
        if (hasTypeSection(definition.type) && ImGui.collapsingHeader(typeName, ImGuiTreeNodeFlags.DefaultOpen)) {
            typeSection(index, definition)
        }
        if (ImGui.collapsingHeader("Interaction")) {
            interaction(index, definition)
        }
        if (ImGui.collapsingHeader("Hooks (${definition.hooks().size})", ImGuiTreeNodeFlags.DefaultOpen)) {
            hooks(index, definition)
        }
        if (ImGui.collapsingHeader("All fields")) {
            advanced(definition)
        }
        ImGui.end()
    }

    // ---------------------------------------------------------------- header

    private fun header(index: Int, definition: InterfaceComponentDefinition, state: ComponentState?) {
        val selection = context.selection.size
        if (selection > 1) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.78f, 0.35f, 1f)
            ImGui.text("$selection components selected - edits apply to all of them")
            ImGui.popStyleColor()
        }
        ImGui.text("Component $index")
        ImGui.sameLine()
        ImGuiText.disabled("hash 0x%08X".format(context.document?.hash(index) ?: 0))
        ImGui.sameLine()
        if (ImGui.smallButton("copy##hash")) {
            ImGui.setClipboardText("0x%08X".format(context.document?.hash(index) ?: 0))
        }

        val type = ImInt(definition.type)
        ImGui.setNextItemWidth(150f)
        if (ImGui.combo("Type", type, TYPE_NAMES)) {
            val chosen = type.get()
            context.document?.let { document ->
                document.selected = index
                document.setType(index, chosen, applyDefaults = false)
                context.touched()
            }
        }
        ImGui.sameLine()
        ImGuiText.disabled("(${definition.type})")

        ImGuiText.disabled(
            if (definition.parent < 0) "parent: root" else "parent: ${definition.parent}"
        )
        if (definition.parent >= 0) {
            ImGui.sameLine()
            if (ImGui.smallButton("go##parent")) {
                context.reveal(definition.parent)
            }
        }
        if (definition.contentType != 0) {
            ImGui.sameLine()
            ImGuiText.disabled("content ${definition.contentType}")
        }
        stringRow("Name (op base)", "name", definition.name, index) { target, value -> target.name = value }
        ImGui.separator()
    }

    // ---------------------------------------------------------------- layout

    private fun layout(index: Int, definition: InterfaceComponentDefinition, state: ComponentState?) {
        ImGuiText.disabled("authored")
        ImGui.sameLine(RESOLVED_COLUMN)
        ImGuiText.disabled("resolved")

        intRow("X", definition.basePositionX, "x", index) { target, value -> target.basePositionX = value }
        ImGui.sameLine(RESOLVED_COLUMN)
        ImGuiText.disabled(state?.let { "x ${it.x}  screen ${it.screenX}" } ?: "-")
        modeRow("X mode", definition.horizontalPositionMode.toInt(), POSITION_MODES, "xmode", index) { target, value ->
            target.horizontalPositionMode = value.toByte()
        }

        intRow("Y", definition.basePositionY, "y", index) { target, value -> target.basePositionY = value }
        ImGui.sameLine(RESOLVED_COLUMN)
        ImGuiText.disabled(state?.let { "y ${it.y}  screen ${it.screenY}" } ?: "-")
        modeRow("Y mode", definition.verticalPositionMode.toInt(), POSITION_MODES, "ymode", index) { target, value ->
            target.verticalPositionMode = value.toByte()
        }

        ImGui.separator()
        intRow("Width", definition.baseWidth, "w", index) { target, value -> target.baseWidth = value }
        ImGui.sameLine(RESOLVED_COLUMN)
        ImGuiText.disabled(state?.let { "${it.width} px" } ?: "-")
        modeRow("W mode", definition.horizontalSizeMode.toInt(), SIZE_MODES, "wmode", index) { target, value ->
            target.horizontalSizeMode = value.toByte()
        }

        intRow("Height", definition.baseHeight, "h", index) { target, value -> target.baseHeight = value }
        ImGui.sameLine(RESOLVED_COLUMN)
        ImGuiText.disabled(state?.let { "${it.height} px" } ?: "-")
        modeRow("H mode", definition.verticalSizeMode.toInt(), SIZE_MODES, "hmode", index) { target, value ->
            target.verticalSizeMode = value.toByte()
        }

        if (state != null && (state.width <= 0 || state.height <= 0)) {
            warn("Zero sized: it draws nothing, and if it is a layer nothing inside it draws either.")
        }
        if (definition.type == ComponentType.CONTAINER) {
            ImGui.separator()
            intRow("Scroll width", definition.scrollWidth, "sw", index) { target, value -> target.scrollWidth = value }
            intRow("Scroll height", definition.scrollHeight, "sh", index) { target, value -> target.scrollHeight = value }
            if (state != null) {
                ImGuiText.disabled("scrolled to ${state.scrollX}, ${state.scrollY}")
            }
        }
    }

    // ------------------------------------------------------------ appearance

    private fun appearance(index: Int, definition: InterfaceComponentDefinition) {
        val hidden = ImBoolean(definition.hidden)
        if (ImGui.checkbox("Hidden", hidden)) {
            val value = hidden.get()
            context.editSelection("Hidden", "hidden@$index") { it.hidden = value }
        }
        ImGui.sameLine()
        val noClickThrough = ImBoolean(definition.disableHover)
        if (ImGui.checkbox("Blocks clicks behind", noClickThrough)) {
            val value = noClickThrough.get()
            context.editSelection("No click through", "nct@$index") { it.disableHover = value }
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "The decoder calls this disableHover; the client field is preventClickThrough. " +
                    "A component with it set swallows the pointer from everything drawn behind it."
            )
        }

        val colour = floatArrayOf(
            ((definition.colour shr 16) and 0xff) / 255f,
            ((definition.colour shr 8) and 0xff) / 255f,
            (definition.colour and 0xff) / 255f,
        )
        if (ImGui.colorEdit3("Colour", colour)) {
            val packed = pack(colour)
            context.editSelection("Colour", "colour@$index") { it.colour = packed }
        }
        closeOnRelease()
        ImGui.sameLine()
        ImGuiText.disabled("#%06X".format(definition.colour and 0xffffff))

        val alpha = intArrayOf(definition.alpha)
        ImGui.setNextItemWidth(160f)
        if (ImGui.sliderInt("Transparency", alpha, 0, 255)) {
            val value = alpha[0]
            context.editSelection("Transparency", "alpha@$index") { it.alpha = value }
        }
        closeOnRelease()
        ImGui.sameLine()
        ImGuiText.disabled("0 = opaque")
    }

    // ----------------------------------------------------------------- types

    private fun hasTypeSection(type: Int): Boolean = when (type) {
        ComponentType.FIGURE, ComponentType.TEXT, ComponentType.GRAPHIC,
        ComponentType.MODEL, ComponentType.LINE -> true
        else -> false
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun typeSection(index: Int, definition: InterfaceComponentDefinition) {
        when (definition.type) {
            ComponentType.FIGURE -> {
                val filled = ImBoolean(definition.filled)
                if (ImGui.checkbox("Filled", filled)) {
                    val value = filled.get()
                    context.editSelection("Filled", "filled@$index") { it.filled = value }
                }
            }
            ComponentType.TEXT -> {
                val buffer = string("text", definition.text)
                if (ImGui.inputTextMultiline("Text", buffer, -1f, 56f)) {
                    val value = buffer.get()
                    context.editSelection("Text", "text@$index") { it.text = value }
                }
                closeOnRelease()
                val font = ImInt(definition.fontId)
                if (assets.fontField("Font", font)) {
                    val value = font.get()
                    context.editSelection("Font", "font@$index") { it.fontId = value }
                }
                comboRow("Align H", definition.horizontalTextAlign, ALIGN_H, "alignh", index) { target, value ->
                    target.horizontalTextAlign = value
                }
                comboRow("Align V", definition.verticalTextAlign, ALIGN_V, "alignv", index) { target, value ->
                    target.verticalTextAlign = value
                }
                intRow("Line height", definition.lineHeight, "lineheight", index) { target, value ->
                    target.lineHeight = value
                }
                ImGui.sameLine()
                ImGuiText.disabled("0 = the font's own")
                val shaded = ImBoolean(definition.shaded)
                if (ImGui.checkbox("Shadow", shaded)) {
                    val value = shaded.get()
                    context.editSelection("Shadow", "shaded@$index") { it.shaded = value }
                }
                if (definition.lineCount != 0) {
                    warn("lineCount is neither read nor written by the if3 codec; it becomes 0 on save.")
                }
            }
            ComponentType.GRAPHIC -> {
                val graphic = ImInt(definition.defaultImage)
                if (assets.graphicField("Graphic", graphic)) {
                    val value = graphic.get()
                    context.editSelection("Graphic", "graphic@$index") { it.defaultImage = value }
                }
                intRow("Angle", definition.imageRotation, "angle", index) { target, value ->
                    target.imageRotation = value
                }
                ImGui.sameLine()
                ImGuiText.disabled("65536 per turn")
                intRow("Outline", definition.rotation, "outline", index) { target, value -> target.rotation = value }
                ImGui.sameLine()
                ImGuiText.disabled("the decoder calls this rotation")
                val tiled = ImBoolean(definition.aBoolean4861)
                if (ImGui.checkbox("Tile", tiled)) {
                    val value = tiled.get()
                    context.editSelection("Tile", "tile@$index") { it.aBoolean4861 = value }
                }
                ImGui.sameLine()
                val flipV = ImBoolean(definition.flipVertical)
                if (ImGui.checkbox("Flip V", flipV)) {
                    val value = flipV.get()
                    context.editSelection("Flip V", "flipv@$index") { it.flipVertical = value }
                }
                ImGui.sameLine()
                val flipH = ImBoolean(definition.flipHorizontal)
                if (ImGui.checkbox("Flip H", flipH)) {
                    val value = flipH.get()
                    context.editSelection("Flip H", "fliph@$index") { it.flipHorizontal = value }
                }
            }
            ComponentType.MODEL -> {
                comboRow("Media", definition.defaultMediaType, MEDIA_TYPES, "media", index) { target, value ->
                    target.defaultMediaType = value
                }
                intRow("Media id", definition.defaultMediaId, "mediaid", index) { target, value ->
                    target.defaultMediaId = value
                }
                intRow("Animation", definition.animation, "anim", index) { target, value -> target.animation = value }
                intRow("Pitch", definition.graphicPitch, "pitch", index) { target, value -> target.graphicPitch = value }
                ImGui.sameLine()
                intRow("Yaw", definition.graphicYaw, "yaw", index) { target, value -> target.graphicYaw = value }
                intRow("Roll", definition.graphicRoll, "roll", index) { target, value -> target.graphicRoll = value }
                ImGui.sameLine()
                intRow("Zoom", definition.graphicScale, "zoom", index) { target, value -> target.graphicScale = value }
                intRow("Origin X", definition.viewportX, "vpx", index) { target, value -> target.viewportX = value }
                ImGui.sameLine()
                intRow("Origin Y", definition.viewportY, "vpy", index) { target, value -> target.viewportY = value }
                val centre = ImBoolean(definition.centreType)
                if (ImGui.checkbox("Write viewport block", centre)) {
                    val value = centre.get()
                    context.editSelection("Centre type", "centre@$index") { it.centreType = value }
                }
                if (definition.defaultMediaType != MediaType.RAW_MODEL) {
                    warn("Only media type 1 survives a save; the decoder forces it for every type 6.")
                }
                if (definition.viewportZ != 0 && !definition.centreType) {
                    warn("viewportZ is only written inside the centreType block; the whole viewport is lost on save.")
                }
            }
            ComponentType.LINE -> {
                intRow("Thickness", definition.lineWidth, "linew", index) { target, value ->
                    target.lineWidth = value.coerceAtLeast(1)
                }
                val mirrored = ImBoolean(definition.lineMirrored)
                if (ImGui.checkbox("Mirrored", mirrored)) {
                    val value = mirrored.get()
                    context.editSelection("Mirrored", "mirror@$index") { it.lineMirrored = value }
                }
            }
        }
        itemRow(index)
    }

    /**
     * The item slot. It is not a definition field - only `IF_SETOBJECT` sets it -
     * so it writes to the live state and is lost on the next re-seed, which is
     * why it is labelled as a preview rather than as a property.
     */
    private fun itemRow(index: Int) {
        val state = context.state(index) ?: return
        if (state.type != ComponentType.GRAPHIC && state.type != ComponentType.MODEL &&
            state.type != ComponentType.TEXT
        ) {
            return
        }
        ImGui.separator()
        ImGuiText.disabled("item preview (live only, IF_SETOBJECT)")
        val item = ImInt(state.itemId)
        if (assets.itemField("Item", item, state.itemAmount)) {
            state.setObject(item.get(), state.itemAmount.coerceAtLeast(1))
        }
        val amount = ImInt(state.itemAmount)
        ImGui.setNextItemWidth(90f)
        if (ImGui.inputInt("Amount", amount, 0)) {
            state.setObject(state.itemId, amount.get())
        }
    }

    // ----------------------------------------------------------- interaction

    @Suppress("LongMethod")
    private fun interaction(index: Int, definition: InterfaceComponentDefinition) {
        val events = definition.setting.setting
        ImGuiText.disabled("events 0x%06X".format(events and 0xffffff))

        bit(index, events, 0, "Continue option")
        for (option in 0 until OPTIONS) {
            val label = definition.options?.getOrNull(option).orEmpty()
            bit(index, events, option + 1, "Option ${option + 1}${if (label.isEmpty()) "" else "  \"$label\""}")
        }
        bit(index, events, DROP_TARGET_BIT, "Accepts drops")
        bit(index, events, USE_TARGET_BIT, "Can be used on")
        bit(index, events, DRAG_UNBOUNDED_BIT, "Drag across the whole frame")

        val depth = intArrayOf((events shr DRAG_DEPTH_SHIFT) and 0x7)
        ImGui.setNextItemWidth(140f)
        if (ImGui.sliderInt("Drag depth", depth, 0, 7)) {
            val value = depth[0]
            context.editSelection("Drag depth", "dragdepth@$index") {
                it.setting.setting = (it.setting.setting and (0x7 shl DRAG_DEPTH_SHIFT).inv()) or
                    (value shl DRAG_DEPTH_SHIFT)
            }
        }
        closeOnRelease()
        ImGui.sameLine()
        ImGuiText.disabled("0 = not draggable")

        val useFlags = intArrayOf((events shr USE_FLAGS_SHIFT) and 0x7f)
        ImGui.setNextItemWidth(140f)
        if (ImGui.sliderInt("Use-on classes", useFlags, 0, 0x7f)) {
            val value = useFlags[0]
            context.editSelection("Use flags", "useflags@$index") {
                it.setting.setting = (it.setting.setting and (0x7f shl USE_FLAGS_SHIFT).inv()) or
                    (value shl USE_FLAGS_SHIFT)
            }
        }
        closeOnRelease()

        ImGui.separator()
        ImGuiText.disabled("Options")
        for (option in 0 until OPTIONS) {
            val current = definition.options?.getOrNull(option).orEmpty()
            val buffer = string("op$option", current)
            ImGui.setNextItemWidth(180f)
            if (ImGui.inputText("op ${option + 1}", buffer)) {
                val value = buffer.get()
                context.editSelection("Option ${option + 1}", "op$option@$index") { target ->
                    val grown = growOptions(target, option)
                    grown[option] = value
                    target.options = grown
                }
            }
            closeOnRelease()
        }

        ImGui.separator()
        stringRow("Use verb", "useOption", definition.useOption, index) { target, value -> target.useOption = value }
        stringRow("Continue text", "resume", definition.optionOverride.orEmpty(), index) { target, value ->
            target.optionOverride = value.ifEmpty { null }
        }
        intRow("Drag deadzone", definition.anInt4708, "deadzone", index) { target, value -> target.anInt4708 = value }
        ImGui.sameLine()
        ImGuiText.disabled("px")
        intRow("Drag dead time", definition.anInt4795, "deadtime", index) { target, value -> target.anInt4795 = value }
        ImGui.sameLine()
        ImGuiText.disabled("client cycles")
        intRow("Target cursor", definition.anInt4698, "targetcursor", index) { target, value -> target.anInt4698 = value }

        keyBindings(index, definition)
    }

    /**
     * The eleven keyboard bindings, printed against what each array really holds
     * rather than what the decoder named it: `keyRepeats` is the key code,
     * `keyCodes` is the modifier mask and `keyModifiers` is the repeat count.
     */
    private fun keyBindings(index: Int, definition: InterfaceComponentDefinition) {
        ImGui.separator()
        val codes = definition.keyRepeats
        ImGuiText.disabled("Key bindings")
        if (codes == null) {
            if (ImGui.smallButton("Add key bindings")) {
                context.editPrimary("Add key bindings", null) { target ->
                    target.keyRepeats = ByteArray(KEY_SLOTS)
                    target.keyCodes = ByteArray(KEY_SLOTS)
                    target.keyModifiers = IntArray(KEY_SLOTS)
                }
            }
            return
        }
        ImGuiText.disabled("codes come from keyRepeats, modifiers from keyCodes, repeats from keyModifiers")
        for (slot in codes.indices) {
            val code = codes[slot].toInt()
            val modifiers = definition.keyCodes?.getOrNull(slot)?.toInt() ?: 0
            val repeats = definition.keyModifiers?.getOrNull(slot) ?: 0
            if (code == 0 && modifiers == 0 && repeats == 0) {
                continue
            }
            val name = if (slot == OPTIONS) "select for use" else "option ${slot + 1}"
            ImGuiText.disabled("$name: code $code, modifiers ${modifierText(modifiers)}, repeat $repeats")
        }
        if (ImGui.smallButton("Clear key bindings")) {
            context.editPrimary("Clear key bindings", null) { target ->
                target.keyRepeats = null
                target.keyCodes = null
                target.keyModifiers = null
            }
        }
    }

    // ----------------------------------------------------------------- hooks

    private fun hooks(index: Int, definition: InterfaceComponentDefinition) {
        val hooks = definition.hooks()
        if (hooks.isEmpty()) {
            ImGuiText.disabled("No hooks on this component.")
            return
        }
        for ((field, hook) in hooks) {
            ImGui.pushID(field)
            val event = EVENT_BY_FIELD[field]
            ImGui.text(event?.name ?: field)
            ImGui.sameLine()
            ImGuiText.disabled("script ${hook.script}")
            if (event != null) {
                ImGui.sameLine()
                if (ImGui.smallButton("run")) {
                    onRunHook(index, event)
                }
            }
            ImGui.sameLine()
            if (ImGui.smallButton("source")) {
                onOpenScript(hook.script)
            }
            ImGui.sameLine()
            if (ImGui.smallButton("clear")) {
                context.editPrimary("Clear $field", null) { target -> clearHook(target, field) }
            }
            ImGuiText.disabled(("  ${field}${arguments(hook)}"))
            ImGui.popID()
        }
    }

    private fun arguments(hook: InterfaceComponentHook): String {
        if (hook.args.isEmpty()) {
            return "()"
        }
        return hook.args.joinToString(", ", "(", ")") { describeArgument(it) }
    }

    private fun describeArgument(value: Any?): String = when (value) {
        is String -> if (value == Cs2HookDispatcher.OPTION_NAME) "<op base>" else "\"$value\""
        is Int -> when (value) {
            Cs2HookDispatcher.MOUSE_X -> "<mouse x>"
            Cs2HookDispatcher.MOUSE_Y -> "<mouse y>"
            Cs2HookDispatcher.SOURCE_COMPONENT -> "<this>"
            Cs2HookDispatcher.CLICKED_OPTION -> "<option>"
            Cs2HookDispatcher.SOURCE_SLOT -> "<slot>"
            Cs2HookDispatcher.TARGET_COMPONENT -> "<target>"
            Cs2HookDispatcher.TARGET_SLOT -> "<target slot>"
            Cs2HookDispatcher.KEY_CODE -> "<key>"
            Cs2HookDispatcher.KEY_CHAR -> "<char>"
            else -> if (value > 0xffff) "0x%08X".format(value) else value.toString()
        }
        else -> value.toString()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun clearHook(target: InterfaceComponentDefinition, field: String) {
        when (field) {
            "onLoadScript" -> target.onLoadScript = null
            "onMouseOver" -> target.onMouseOver = null
            "onMouseLeaveScript" -> target.onMouseLeaveScript = null
            "hookParams" -> target.hookParams = null
            "onTargetEnter" -> target.onTargetEnter = null
            "onVarpTransmit" -> target.onVarpTransmit = null
            "mouseLeaveScript" -> target.mouseLeaveScript = null
            "onStatTransmit" -> target.onStatTransmit = null
            "onTimer" -> target.onTimer = null
            "params" -> target.params = null
            "onTargetLeave" -> target.onTargetLeave = null
            "popupScript" -> target.popupScript = null
            "onClick" -> target.onClick = null
            "onClickRepeat" -> target.onClickRepeat = null
            "onRelease" -> target.onRelease = null
            "onHold" -> target.onHold = null
            "onDrag" -> target.onDrag = null
            "onDragComplete" -> target.onDragComplete = null
            "onMouseMove" -> target.onMouseMove = null
            "onKey" -> target.onKey = null
            "onScrollWheel" -> target.onScrollWheel = null
        }
    }

    // -------------------------------------------------------------- advanced

    /**
     * Every declared field, straight off the definition. Read only on purpose:
     * the grouped sections above are the supported way to change anything, and a
     * reflective setter over a hundred fields is a very efficient way to corrupt
     * a component. This is here to answer "what is actually in this thing".
     */
    private fun advanced(definition: InterfaceComponentDefinition) {
        val toggle = ImBoolean(showAdvanced)
        if (ImGui.checkbox("Show defaults too", toggle)) {
            showAdvanced = toggle.get()
        }
        val defaults = InterfaceComponentDefinition()
        for (field in ComponentFields.fields) {
            val value = ComponentFields.get(definition, field)
            if (!showAdvanced && ComponentFields.same(value, ComponentFields.get(defaults, field))) {
                continue
            }
            ImGuiText.disabled(("${field.name} = ${ComponentFields.describe(value)}"))
        }
    }

    // --------------------------------------------------------------- widgets

    private fun intRow(
        label: String,
        value: Int,
        key: String,
        index: Int,
        apply: (InterfaceComponentDefinition, Int) -> Unit,
    ) {
        val box = ImInt(value)
        ImGui.setNextItemWidth(FIELD_WIDTH)
        if (ImGui.inputInt(label, box, 0)) {
            val typed = box.get()
            context.editSelection(label, "$key@$index") { apply(it, typed) }
        }
        closeOnRelease()
    }

    private fun modeRow(
        label: String,
        value: Int,
        names: Array<String>,
        key: String,
        index: Int,
        apply: (InterfaceComponentDefinition, Int) -> Unit,
    ) = comboRow(label, value, names, key, index, apply)

    private fun comboRow(
        label: String,
        value: Int,
        names: Array<String>,
        key: String,
        index: Int,
        apply: (InterfaceComponentDefinition, Int) -> Unit,
    ) {
        val box = ImInt(value.coerceIn(0, names.size - 1))
        ImGui.setNextItemWidth(COMBO_WIDTH)
        if (ImGui.combo(label, box, names)) {
            val chosen = box.get()
            context.editSelection(label, "$key@$index") { apply(it, chosen) }
        }
        if (value !in names.indices) {
            ImGui.sameLine()
            ImGuiText.disabled("($value)")
        }
    }

    private fun stringRow(
        label: String,
        key: String,
        value: String,
        index: Int,
        apply: (InterfaceComponentDefinition, String) -> Unit,
    ) {
        val buffer = string(key, value)
        ImGui.setNextItemWidth(-1f)
        if (ImGui.inputText("$label##$key", buffer)) {
            val typed = buffer.get()
            context.editSelection(label, "$key@$index") { apply(it, typed) }
        }
        closeOnRelease()
    }

    private fun bit(index: Int, events: Int, bit: Int, label: String) {
        val box = ImBoolean(((events shr bit) and 0x1) != 0)
        if (ImGui.checkbox(label, box)) {
            val on = box.get()
            context.editSelection(label, "bit$bit@$index") { target ->
                target.setting.setting = if (on) {
                    target.setting.setting or (1 shl bit)
                } else {
                    target.setting.setting and (1 shl bit).inv()
                }
            }
        }
    }

    private fun string(key: String, current: String): ImString =
        strings.getOrPut(key) { ImString(current, STRING_CAPACITY) }

    /** Closes the undo step when a widget loses focus, so one gesture is one step. */
    private fun closeOnRelease() {
        if (ImGui.isItemDeactivatedAfterEdit()) {
            context.endEdit()
        }
    }

    private fun warn(text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.75f, 0.3f, 1f)
        ImGuiText.wrapped(text)
        ImGui.popStyleColor()
    }

    private fun growOptions(target: InterfaceComponentDefinition, option: Int): Array<String> {
        val existing = target.options
        val size = maxOf(existing?.size ?: 0, option + 1)
        return Array(size) { existing?.getOrNull(it).orEmpty() }
    }

    private fun modifierText(mask: Int): String {
        if (mask == 0) {
            return "none"
        }
        val parts = ArrayList<String>(4)
        if ((mask and 0x1) != 0) parts.add("Ctrl")
        if ((mask and 0x2) != 0) parts.add("Alt")
        if ((mask and 0x4) != 0) parts.add("Shift")
        if ((mask and 0x8) != 0) parts.add("no modifier")
        return parts.joinToString("+")
    }

    private fun pack(rgb: FloatArray): Int {
        val red = (rgb[0] * 255f).toInt().coerceIn(0, 255)
        val green = (rgb[1] * 255f).toInt().coerceIn(0, 255)
        val blue = (rgb[2] * 255f).toInt().coerceIn(0, 255)
        return (red shl 16) or (green shl 8) or blue
    }

    companion object {
        private const val FIELD_WIDTH = 84f
        private const val COMBO_WIDTH = 200f
        private const val RESOLVED_COLUMN = 210f
        private const val STRING_CAPACITY = 256
        private const val OPTIONS = 10
        private const val KEY_SLOTS = OPTIONS + 1

        private const val USE_FLAGS_SHIFT = 11
        private const val DRAG_DEPTH_SHIFT = 18
        private const val DROP_TARGET_BIT = 21
        private const val USE_TARGET_BIT = 22
        private const val DRAG_UNBOUNDED_BIT = 23

        private val TYPE_NAMES = Array(10) { ComponentType.name(it) }

        /** Size modes, as [org.darkan.tools.rsinterface.InterfaceInstance] resolves them. */
        private val SIZE_MODES = arrayOf(
            "0 absolute",
            "1 inset from parent",
            "2 proportional /16384",
            "3 unchanged",
            "4 aspect of other axis",
        )

        /** Position modes, from the client's componentPositionAdjustment. */
        private val POSITION_MODES = arrayOf(
            "0 absolute",
            "1 centred + offset",
            "2 from bottom/right",
            "3 proportional /16384",
            "4 centred + proportional",
            "5 proportional from end",
        )

        private val ALIGN_H = arrayOf("0 left", "1 centre", "2 right", "3 justified")
        private val ALIGN_V = arrayOf("0 top", "1 middle", "2 bottom")

        private val MEDIA_TYPES = arrayOf(
            "0 none",
            "1 raw model",
            "2 npc head",
            "3 player head",
            "4 item",
            "5 player",
            "6 npc",
            "7 player head (no worn)",
            "8 worn male",
            "9 worn female",
        )

        /** Definition field name to the event that fires it. */
        private val EVENT_BY_FIELD: Map<String, InterfaceEvent> =
            InterfaceEvent.entries.mapNotNull { event -> event.field?.let { it to event } }.toMap()
    }
}
