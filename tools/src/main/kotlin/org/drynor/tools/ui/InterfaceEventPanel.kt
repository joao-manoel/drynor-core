package org.drynor.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiChildFlags
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import imgui.type.ImString
import org.drynor.cs2.vm.Cs2Event
import org.drynor.tools.rsinterface.ComponentState
import org.drynor.tools.rsinterface.InterfaceCs2Host
import org.drynor.tools.rsinterface.InterfaceEvent
import org.drynor.tools.rsinterface.InterfaceInteraction
import org.drynor.tools.rsinterface.InterfaceKeys

/**
 * The "why didn't my hook run" panel.
 *
 * Everything an interface does at runtime goes through
 * [InterfaceInteraction], and when a button does nothing the answer is always one
 * of a short list: the pointer never reached the component, the component has no
 * hook for the event that fired, a script rebound the hook to something else, the
 * script errored, or the editor is in Design mode and nothing is being dispatched
 * at all. This panel shows each of those directly rather than leaving them to be
 * inferred:
 *
 * - the **hover chain** is what the pointer actually reached, back to front,
 *   including the components that a `noClickThrough` above them swallowed;
 * - the **trace** is every dispatch that happened, with the script it ran and
 *   whatever the virtual machine complained about;
 * - the **manual triggers** fire an event without needing to reproduce the
 *   gesture, which is the only practical way to test a transmit hook - a varp
 *   write has no pointer gesture behind it.
 *
 * Firing by hand temporarily lifts [InterfaceInteraction.liveMode], because a
 * "fire this hook" button that silently does nothing in Design mode is worse
 * than no button.
 */
class InterfaceEventPanel(
    private val context: InterfaceEditContext,
    private val interaction: InterfaceInteraction,
    private val host: InterfaceCs2Host,
) {
    var visible: Boolean = true

    /** Live when the viewer is in Play mode; shown so the panel can explain itself. */
    var playMode: Boolean = false

    private val eventChoice = ImInt(InterfaceEvent.MOUSE_PRESS.ordinal)
    private val varpId = ImInt(0)
    private val varpValue = ImInt(0)
    private val varcId = ImInt(0)
    private val varcValue = ImInt(0)
    private val statId = ImInt(0)
    private val invId = ImInt(0)
    private val keyCode = ImInt(0)
    private val keyChar = ImInt(0)
    private val traceFilter = ImString(48)
    private var errorsOnly = false
    private var followTrace = true

    fun render() {
        if (!visible) {
            return
        }
        if (!ImGui.begin("Interaction")) {
            ImGui.end()
            return
        }
        runtimeControls()
        ImGui.separator()

        if (ImGui.collapsingHeader("Live state", ImGuiTreeNodeFlags.DefaultOpen)) {
            liveState()
        }
        if (ImGui.collapsingHeader("Trigger", ImGuiTreeNodeFlags.DefaultOpen)) {
            triggers()
        }
        if (ImGui.collapsingHeader("Bound hooks (${host.boundHooks.size})")) {
            boundHooks()
        }
        if (ImGui.collapsingHeader("Trace (${interaction.trace.size})", ImGuiTreeNodeFlags.DefaultOpen)) {
            trace()
        }
        ImGui.end()
    }

    // -------------------------------------------------------------- controls

    private fun runtimeControls() {
        if (playMode) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.55f, 1f)
            ImGui.text("PLAY - hooks fire from real input")
        } else {
            ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.78f, 0.35f, 1f)
            ImGui.text("DESIGN - input selects and moves; no hooks fire")
        }
        ImGui.popStyleColor()

        val onRelease = ImBoolean(interaction.clickOnRelease)
        if (ImGui.checkbox("Click on release", onRelease)) {
            interaction.clickOnRelease = onRelease.get()
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "The client fires onClick on press. Turn this on for the press-and-release " +
                    "behaviour a normal UI toolkit uses."
            )
        }
        ImGui.sameLine()
        val scroll = ImBoolean(interaction.directScroll)
        if (ImGui.checkbox("Wheel scrolls containers", scroll)) {
            interaction.directScroll = scroll.get()
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "The client scrolls entirely from scrollbar scripts. This moves the nearest " +
                    "overflowing container itself, for interfaces whose scrollbar is not wired up."
            )
        }
        ImGui.sameLine()
        val step = ImInt(interaction.scrollStep)
        ImGui.setNextItemWidth(70f)
        if (ImGui.inputInt("px", step, 0)) {
            interaction.scrollStep = step.get().coerceAtLeast(1)
        }
    }

    // ------------------------------------------------------------ live state

    private fun liveState() {
        val pointer = interaction.pointer
        ImGuiText.disabled(
            "pointer ${pointer.x}, ${pointer.y}   " +
                "buttons ${if (pointer.left) "L" else "-"}${if (pointer.right) "R" else "-"}" +
                "${if (pointer.middle) "M" else "-"}   wheel ${pointer.wheel}"
        )
        val viewport = interaction.viewport
        ImGuiText.disabled("viewport ${viewport.x}, ${viewport.y} ${viewport.width}x${viewport.height}")

        ImGui.text("Hover chain")
        val chain = interaction.hovered
        if (chain.isEmpty()) {
            ImGuiText.disabled("  nothing under the pointer")
        }
        // Front to back reads the way a developer thinks about it: the thing they
        // believe they clicked first, then what is behind it.
        for (state in chain.asReversed()) {
            componentLink("  ", state)
        }

        row("pressed", interaction.pressed)
        row("focused", interaction.focused)
        row("use selection", interaction.selected)
        val drag = interaction.drag
        if (drag == null) {
            ImGuiText.disabled("drag: none")
        } else {
            ImGuiText.disabled(
                "drag: ${drag.source.index} -> ${drag.target?.index ?: "-"} slot ${drag.targetSlot} " +
                    if (drag.active) "(active)" else "(below the dead zone)"
            )
        }
        if (interaction.menuOpen) {
            ImGuiText.disabled("menu open at ${interaction.menuX}, ${interaction.menuY} with ${interaction.menu.size} entries")
        }
    }

    private fun row(label: String, state: ComponentState?) {
        if (state == null) {
            ImGuiText.disabled("$label: none")
            return
        }
        componentLink("$label: ", state)
    }

    private fun componentLink(prefix: String, state: ComponentState) {
        ImGui.pushID(state.index + PREFIX_SALT * prefix.hashCode())
        if (ImGui.smallButton("${state.index}")) {
            context.reveal(state.index)
        }
        ImGui.sameLine()
        ImGuiText.disabled(
            ("$prefix${state.screenX},${state.screenY} ${state.width}x${state.height}" +
                    if (state.definition.name.isEmpty()) "" else "  ${state.definition.name}"
            )
        )
        ImGui.popID()
    }

    // --------------------------------------------------------------- trigger

    private fun triggers() {
        val target = context.primaryState()
        ImGui.setNextItemWidth(200f)
        ImGui.combo("Event", eventChoice, EVENT_NAMES)
        ImGui.sameLine()
        if (ImGui.button("Fire on selection")) {
            fireOnSelection()
        }
        if (target == null) {
            ImGuiText.disabled("Select a component to fire an event on it.")
        } else {
            ImGuiText.disabled("target: component ${target.index}")
        }

        ImGui.separator()
        // The host already routes every var write back into the interaction's
        // transmit entry points, so writing the value is the whole trigger.
        transmitRow("varp", varpId, varpValue) { id, value -> live { host.setVarp(id, value) } }
        transmitRow("varc", varcId, varcValue) { id, value -> live { host.setVarc(id, value) } }
        idRow("stat", statId) { id -> live { interaction.statChanged(id) } }
        idRow("inv", invId) { id -> live { interaction.invChanged(id) } }

        ImGui.separator()
        ImGui.setNextItemWidth(90f)
        ImGui.inputInt("GLFW key", keyCode, 0)
        ImGui.sameLine()
        ImGui.setNextItemWidth(90f)
        ImGui.inputInt("char", keyChar, 0)
        ImGui.sameLine()
        if (ImGui.button("Inject")) {
            val code = keyCode.get()
            live {
                interaction.key(code, keyChar.get().toChar(), 0)
                interaction.keyReleased(code)
            }
        }
        ImGuiText.disabled("client code ${InterfaceKeys.fromGlfw(keyCode.get())}")
    }

    private fun transmitRow(label: String, id: ImInt, value: ImInt, apply: (Int, Int) -> Unit) {
        ImGui.pushID(label)
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("id", id, 0)
        ImGui.sameLine()
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("value", value, 0)
        ImGui.sameLine()
        if (ImGui.button("set $label")) {
            apply(id.get(), value.get())
        }
        ImGui.popID()
    }

    private fun idRow(label: String, id: ImInt, apply: (Int) -> Unit) {
        ImGui.pushID(label)
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("id", id, 0)
        ImGui.sameLine()
        if (ImGui.button("$label changed")) {
            apply(id.get())
        }
        ImGui.popID()
    }

    private fun fireOnSelection() {
        val state = context.primaryState() ?: return
        val event = InterfaceEvent.entries.getOrNull(eventChoice.get()) ?: return
        live {
            interaction.dispatch(
                state,
                event,
                Cs2Event(
                    mouseX = interaction.pointer.x - state.screenX,
                    mouseY = interaction.pointer.y - state.screenY,
                    source = state.hash,
                    clickedOption = 1,
                    optionName = state.opBase,
                ),
            )
        }
    }

    /**
     * Runs a dispatch with the runtime live, whatever mode the editor is in.
     * Everything in this panel is an explicit request to run something, so
     * honouring Design mode here would only make the buttons look broken.
     */
    private fun live(block: () -> Unit) {
        val previous = interaction.liveMode
        interaction.liveMode = true
        try {
            block()
        } finally {
            interaction.liveMode = previous
        }
    }

    // ----------------------------------------------------------- bound hooks

    private fun boundHooks() {
        if (host.boundHooks.isEmpty()) {
            ImGuiText.disabled("No script has bound a hook. Run the load scripts first.")
            return
        }
        val instance = context.instance
        for ((hash, events) in host.boundHooks) {
            val state = instance?.stateByHash(hash)
            ImGui.pushID(hash)
            if (ImGui.smallButton(if (state == null) "?" else "${state.index}")) {
                state?.let { context.reveal(it.index) }
            }
            ImGui.sameLine()
            ImGui.text("0x%08X".format(hash))
            for ((event, hook) in events) {
                ImGuiText.disabled("    $event -> script ${hook.scriptId}")
            }
            ImGui.popID()
        }
    }

    // ----------------------------------------------------------------- trace

    private fun trace() {
        ImGui.setNextItemWidth(160f)
        ImGui.inputTextWithHint("##tracefilter", "event or component", traceFilter)
        ImGui.sameLine()
        val errors = ImBoolean(errorsOnly)
        if (ImGui.checkbox("errors", errors)) {
            errorsOnly = errors.get()
        }
        ImGui.sameLine()
        val follow = ImBoolean(followTrace)
        if (ImGui.checkbox("follow", follow)) {
            followTrace = follow.get()
        }
        ImGui.sameLine()
        if (ImGui.smallButton("clear")) {
            interaction.clearTrace()
        }

        val filter = traceFilter.get().trim().lowercase()
        if (!ImGui.beginChild("tracelist", 0f, TRACE_HEIGHT, ImGuiChildFlags.Borders)) {
            ImGui.endChild()
            return
        }
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.SizingStretchProp or ImGuiTableFlags.BordersInnerV
        if (ImGui.beginTable("trace", 4, flags)) {
            ImGui.tableSetupColumn("component", ImGuiTableColumnFlags.WidthFixed, 74f)
            ImGui.tableSetupColumn("event", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("script", ImGuiTableColumnFlags.WidthFixed, 62f)
            ImGui.tableSetupColumn("from", ImGuiTableColumnFlags.WidthFixed, 48f)
            ImGui.tableHeadersRow()
            for ((position, entry) in interaction.trace.withIndex()) {
                if (errorsOnly && entry.error == null) {
                    continue
                }
                if (filter.isNotEmpty() &&
                    !entry.event.name.lowercase().contains(filter) &&
                    entry.component.toString() != filter
                ) {
                    continue
                }
                ImGui.tableNextRow()
                ImGui.tableSetColumnIndex(0)
                ImGui.pushID(position)
                if (ImGui.smallButton("${entry.component}")) {
                    context.reveal(entry.component)
                }
                ImGui.popID()
                ImGui.tableSetColumnIndex(1)
                if (entry.error == null) {
                    ImGui.text(entry.event.name)
                } else {
                    ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.45f, 0.4f, 1f)
                    ImGui.text(entry.event.name)
                    ImGui.popStyleColor()
                    if (ImGui.isItemHovered()) {
                        ImGuiText.tooltip((entry.error))
                    }
                }
                ImGui.tableSetColumnIndex(2)
                ImGuiText.disabled(entry.script.toString())
                ImGui.tableSetColumnIndex(3)
                ImGuiText.disabled(if (entry.bound) "script" else "cache")
            }
            ImGui.endTable()
        }
        if (followTrace) {
            ImGui.setScrollHereY(1f)
        }
        ImGui.endChild()
    }

    companion object {
        private const val TRACE_HEIGHT = 190f

        /** Keeps the ImGui ids of two lists of the same components apart. */
        private const val PREFIX_SALT = 31

        private val EVENT_NAMES = InterfaceEvent.entries.map { it.name }.toTypedArray()
    }
}
