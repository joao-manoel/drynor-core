package org.drynor.tools.ui

import imgui.ImGui
import imgui.ImGuiListClipper
import imgui.callback.ImListClipperCallback
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiSelectableFlags
import imgui.type.ImBoolean
import imgui.type.ImString
import org.drynor.tools.rsinterface.ComponentState
import org.drynor.tools.rsinterface.ComponentTemplates
import org.drynor.tools.rsinterface.ComponentType
import org.drynor.tools.rsinterface.InterfaceDocument
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP

/**
 * The component tree.
 *
 * ### Why the tree is flattened before it is drawn
 *
 * ImGui's `TreeNode` recursion lays out every node it is asked for, visible or
 * not, and cache interfaces run to several hundred components - 1477 alone is
 * over four hundred. Recursing costs a widget per component per frame and the
 * panel visibly slows down. So the hierarchy is flattened into a row list once
 * per frame, respecting the collapse state, and [ImGuiListClipper] emits only the
 * rows inside the scroll region. Indentation is then drawn by hand, which is the
 * price of the clipper and is why the expander is a real button rather than a
 * tree node's arrow.
 *
 * ### Why the drag is hand rolled
 *
 * ImGui's drag-and-drop payload API would do the gesture, but not the part that
 * matters: a reparent and a reorder are the same gesture distinguished only by
 * *where in the row* the pointer is, and the drop indicator has to say which one
 * is about to happen. Tracking the press, the hovered row and the third of the
 * row the pointer is in gives all three - drop above, drop inside, drop below -
 * from one piece of state.
 *
 * ### What the rows say
 *
 * A row is a diagnostic, not a label. The status letters after the name are the
 * four things that make a component invisible or inert, and they are exactly the
 * questions asked while debugging an interface that draws nothing: is it hidden,
 * does it have a hook, is it zero sized, is it outside the stage.
 */
class InterfaceEditorPanel(private val context: InterfaceEditContext) {

    var visible: Boolean = true

    /** Stage size, so a row can say when a component landed outside it. */
    var stageWidth: Int = 0
    var stageHeight: Int = 0

    /**
     * This frame's key presses, handed over by the viewer because ImGui's own
     * key enum does not line up with GLFW's in this binding version. Consumed
     * only while the tree has focus.
     */
    var keys: List<Pair<Int, Int>> = emptyList()

    private val search = ImString(64)
    private var showHooksOnly = false
    private var showHiddenOnly = false

    /** Collapsed component indices; everything else is open. */
    private val collapsed = HashSet<Int>()

    private var rows: List<Row> = emptyList()
    private var rowsRevision = -1
    private var rowsQuery = ""
    private var rowsFilters = 0

    private var dragIndex = -1
    private var dropTarget = -1
    private var dropMode = DROP_NONE

    /** Row the clipper should scroll into view, set when something reveals a component. */
    private var scrollTo = -1

    private class Row(val index: Int, val depth: Int, val hasChildren: Boolean, val open: Boolean)

    fun render() {
        if (!visible) {
            return
        }
        if (!ImGui.begin("Tree")) {
            ImGui.end()
            return
        }
        val document = context.document
        if (document == null) {
            ImGuiText.disabled("No interface open.")
            ImGui.end()
            return
        }

        drawToolbar(document)
        rebuildRows(document)
        honourReveal()

        ImGui.separator()
        if (ImGui.beginChild("rows", 0f, 0f)) {
            drawRows(document)
            handleKeys()
        }
        ImGui.endChild()
        finishDrag(document)
        ImGui.end()
    }

    // ---------------------------------------------------------------- toolbar

    private fun drawToolbar(document: InterfaceDocument) {
        ImGui.setNextItemWidth(-1f)
        ImGui.inputTextWithHint("##search", "filter by name, text, index or type", search)

        val hooks = ImBoolean(showHooksOnly)
        if (ImGui.checkbox("hooked", hooks)) {
            showHooksOnly = hooks.get()
        }
        ImGui.sameLine()
        val hidden = ImBoolean(showHiddenOnly)
        if (ImGui.checkbox("hidden", hidden)) {
            showHiddenOnly = hidden.get()
        }
        ImGui.sameLine()
        if (ImGui.smallButton("expand")) {
            collapsed.clear()
        }
        ImGui.sameLine()
        if (ImGui.smallButton("collapse")) {
            collapsed.clear()
            for (index in 0 until document.componentCount) {
                if (document.children(index).isNotEmpty()) {
                    collapsed.add(index)
                }
            }
        }
        ImGui.sameLine()
        ImGuiText.disabled("${document.componentCount}")
    }

    // ------------------------------------------------------------------ rows

    /**
     * Rebuilds the flat row list, which only changes when the document, the
     * collapse state or the filter does.
     */
    private fun rebuildRows(document: InterfaceDocument) {
        val query = search.get().trim()
        val filters = (if (showHooksOnly) 1 else 0) or (if (showHiddenOnly) 2 else 0)
        if (rowsRevision == context.revision && rowsQuery == query && rowsFilters == filters) {
            return
        }
        rowsRevision = context.revision
        rowsQuery = query
        rowsFilters = filters

        val built = ArrayList<Row>(document.componentCount)
        if (query.isEmpty() && filters == 0) {
            appendLevel(document, InterfaceDocument.ROOT, 0, built)
        } else {
            // A filtered tree is a flat list: hiding the parents of a match while
            // keeping the indentation reads as a broken hierarchy, and a developer
            // filtering wants the hits, not their ancestry.
            val lower = query.lowercase()
            for (index in 0 until document.componentCount) {
                if (matches(document, index, lower)) {
                    built.add(Row(index, 0, false, false))
                }
            }
        }
        rows = built
    }

    private fun appendLevel(document: InterfaceDocument, parent: Int, depth: Int, into: MutableList<Row>) {
        for (index in document.children(parent)) {
            val children = document.children(index)
            val open = index !in collapsed
            into.add(Row(index, depth, children.isNotEmpty(), open))
            if (open && children.isNotEmpty()) {
                appendLevel(document, index, depth + 1, into)
            }
        }
    }

    private fun matches(document: InterfaceDocument, index: Int, query: String): Boolean {
        val definition = document.component(index) ?: return false
        if (showHooksOnly && definition.hooks().isEmpty()) {
            return false
        }
        if (showHiddenOnly && !definition.hidden) {
            return false
        }
        if (query.isEmpty()) {
            return true
        }
        return index.toString() == query ||
            definition.name.contains(query, ignoreCase = true) ||
            definition.text.contains(query, ignoreCase = true) ||
            ComponentType.name(definition.type).contains(query, ignoreCase = true)
    }

    /** Opens every ancestor of a revealed component and queues a scroll to it. */
    private fun honourReveal() {
        val target = context.revealed
        if (target < 0) {
            return
        }
        context.revealed = -1
        val document = context.document ?: return
        var parent = document.component(target)?.parent ?: InterfaceDocument.ROOT
        var guard = document.componentCount
        while (parent >= 0 && guard-- > 0) {
            collapsed.remove(parent)
            parent = document.component(parent)?.parent ?: InterfaceDocument.ROOT
        }
        rowsRevision = -1
        rebuildRows(document)
        scrollTo = target
    }

    private fun drawRows(document: InterfaceDocument) {
        if (rows.isEmpty()) {
            ImGuiText.disabled("Nothing matches.")
            return
        }
        // The clipper needs the row list captured outside the callback; it is
        // invoked synchronously but Kotlin cannot see that through the interface.
        val visibleRows = rows
        dropTarget = -1
        dropMode = DROP_NONE
        ImGuiListClipper.forEach(visibleRows.size, object : ImListClipperCallback() {
            override fun accept(index: Int) {
                val row = visibleRows.getOrNull(index) ?: return
                drawRow(document, row)
            }
        })
        if (scrollTo >= 0) {
            // The clipper only lays out the visible span, so a scroll target that
            // is outside it has to be reached arithmetically rather than by
            // setScrollHereY on a row that was never emitted.
            val position = visibleRows.indexOfFirst { it.index == scrollTo }
            if (position >= 0) {
                ImGui.setScrollY(position * ImGui.getTextLineHeightWithSpacing() - ImGui.getWindowSizeY() / 2f)
            }
            scrollTo = -1
        }
    }

    @Suppress("LongMethod")
    private fun drawRow(document: InterfaceDocument, row: Row) {
        val index = row.index
        val definition = document.component(index) ?: return
        val state = context.state(index)

        ImGui.pushID(index)
        if (row.depth > 0) {
            ImGui.dummy(row.depth * INDENT, 0f)
            ImGui.sameLine(0f, 0f)
        }
        if (row.hasChildren) {
            if (ImGui.smallButton(if (row.open) "-" else "+")) {
                if (!collapsed.remove(index)) {
                    collapsed.add(index)
                }
                rowsRevision = -1
            }
        } else {
            ImGui.dummy(LEAF_WIDTH, 0f)
        }
        ImGui.sameLine(0f, 4f)

        val dim = definition.hidden
        val colour = typeColour(definition.type)
        ImGui.pushStyleColor(
            ImGuiCol.Text,
            if (dim) colour[0] * DIM else colour[0],
            if (dim) colour[1] * DIM else colour[1],
            if (dim) colour[2] * DIM else colour[2],
            1f,
        )
        val clicked = ImGui.selectable(
            label(document, index, definition, state, row.depth),
            context.isSelected(index),
            ImGuiSelectableFlags.AllowDoubleClick,
        )
        ImGui.popStyleColor()

        val hovered = ImGui.isItemHovered()
        val top = ImGui.getItemRectMinY()
        val bottom = ImGui.getItemRectMaxY()
        val left = ImGui.getItemRectMinX()
        val right = ImGui.getItemRectMaxX()

        if (clicked) {
            select(document, index)
        }
        if (ImGui.isItemClicked(ImGuiMouseButton.Right) && !context.isSelected(index)) {
            context.select(index)
        }
        contextMenu(document, index, definition)

        // Press then move is a drag; the press alone is a click, which is what
        // makes selecting and dragging the same gesture up to the first pixel.
        if (dragIndex < 0 && ImGui.isItemActive() && ImGui.isMouseDragging(ImGuiMouseButton.Left, DRAG_THRESHOLD)) {
            dragIndex = index
        }
        if (dragIndex >= 0 && dragIndex != index && hovered) {
            val height = (bottom - top).coerceAtLeast(1f)
            val fraction = (ImGui.getMousePosY() - top) / height
            dropTarget = index
            dropMode = when {
                fraction < EDGE_ZONE -> DROP_BEFORE
                fraction > 1f - EDGE_ZONE -> DROP_AFTER
                else -> DROP_INSIDE
            }
            drawDropIndicator(left, top, right, bottom)
        }
        ImGui.popID()
    }

    private fun drawDropIndicator(left: Float, top: Float, right: Float, bottom: Float) {
        val drawList = ImGui.getWindowDrawList()
        when (dropMode) {
            DROP_BEFORE -> drawList.addLine(left, top, right, top, DROP_COLOUR, 2f)
            DROP_AFTER -> drawList.addLine(left, bottom, right, bottom, DROP_COLOUR, 2f)
            else -> drawList.addRect(left, top, right, bottom, DROP_COLOUR, 0f, 0, 2f)
        }
    }

    /**
     * Applies a finished drag. Dropping inside reparents; dropping on an edge
     * reorders within the target's parent, which is the only way to change draw
     * order - and draw order is what decides which component is on top.
     */
    private fun finishDrag(document: InterfaceDocument) {
        if (dragIndex < 0) {
            return
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            val target = dropTarget
            val mode = dropMode
            val moving = if (context.isSelected(dragIndex)) context.targets() else listOf(dragIndex)
            dragIndex = -1
            dropTarget = -1
            dropMode = DROP_NONE
            if (target < 0 || mode == DROP_NONE) {
                return
            }
            context.structural { current ->
                if (mode == DROP_INSIDE) {
                    current.reparentAll(moving, target)
                } else {
                    val parent = current.component(target)?.parent ?: InterfaceDocument.ROOT
                    val siblings = current.children(parent).filter { it !in moving }
                    val at = siblings.indexOf(target)
                    val insertAt = when {
                        at < 0 -> -1
                        mode == DROP_AFTER -> at + 1
                        else -> at
                    }
                    current.reparentAll(moving, parent, insertAt)
                }
            }
            rowsRevision = -1
        }
    }

    private fun select(document: InterfaceDocument, index: Int) {
        val io = ImGui.getIO()
        when {
            io.keyCtrl -> context.select(index, additive = true)
            io.keyShift && context.primary >= 0 -> selectRange(context.primary, index)
            else -> context.select(index)
        }
        if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left) && document.children(index).isNotEmpty()) {
            if (!collapsed.remove(index)) {
                collapsed.add(index)
            }
            rowsRevision = -1
        }
    }

    /** Shift-click selects everything between the two rows as they are displayed. */
    private fun selectRange(from: Int, to: Int) {
        val start = rows.indexOfFirst { it.index == from }
        val end = rows.indexOfFirst { it.index == to }
        if (start < 0 || end < 0) {
            context.select(to)
            return
        }
        val span = if (start <= end) start..end else end..start
        context.setSelection(span.map { rows[it].index }, to)
    }

    // ------------------------------------------------------------ row labels

    private fun label(
        document: InterfaceDocument,
        index: Int,
        definition: InterfaceComponentDefinition,
        state: ComponentState?,
        depth: Int,
    ): String {
        val builder = StringBuilder(48)
        builder.append(index).append("  ").append(typeTag(definition.type)).append(' ')
        val name = when {
            definition.name.isNotEmpty() -> definition.name
            definition.type == ComponentType.TEXT && definition.text.isNotEmpty() -> "\"${strip(definition.text)}\""
            definition.type == ComponentType.GRAPHIC && definition.defaultImage >= 0 -> "#${definition.defaultImage}"
            definition.type == ComponentType.MODEL && definition.defaultMediaId > 0 -> "model ${definition.defaultMediaId}"
            else -> ComponentType.name(definition.type)
        }
        builder.append(name.take(NAME_LIMIT))

        val flags = StringBuilder(8)
        if (definition.hidden) {
            flags.append('H')
        }
        if (definition.hooks().isNotEmpty()) {
            flags.append('S')
        }
        if (definition.setting.setting != 0) {
            flags.append('O')
        }
        if (state != null && (state.width <= 0 || state.height <= 0)) {
            flags.append('0')
        }
        if (state != null && offStage(state)) {
            flags.append('!')
        }
        if (flags.isNotEmpty()) {
            builder.append("   ").append(flags)
        }
        // Depth is already drawn as indentation in the unfiltered tree; in the flat
        // filtered list the parent is the only thing that locates a component.
        if (depth == 0 && definition.parent >= 0) {
            builder.append("   in ").append(definition.parent)
        }
        builder.append("##row").append(index)
        return builder.toString()
    }

    private fun offStage(state: ComponentState): Boolean {
        if (stageWidth <= 0 || stageHeight <= 0 || state.width <= 0 || state.height <= 0) {
            return false
        }
        return state.screenX + state.width <= 0 || state.screenY + state.height <= 0 ||
            state.screenX >= stageWidth || state.screenY >= stageHeight
    }

    private fun strip(text: String): String = text.replace(TAG, "").trim()

    // --------------------------------------------------------- context menu

    @Suppress("LongMethod")
    private fun contextMenu(
        document: InterfaceDocument,
        index: Int,
        definition: InterfaceComponentDefinition,
    ) {
        if (!ImGui.beginPopupContextItem("ctx")) {
            return
        }
        val targets = context.targets()
        ImGuiText.disabled(if (targets.size > 1) "${targets.size} components" else "component $index")
        ImGui.separator()

        if (ImGui.beginMenu("Add child")) {
            templateMenu(index)
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Add sibling")) {
            templateMenu(definition.parent)
            ImGui.endMenu()
        }
        ImGui.separator()

        if (ImGui.menuItem("Duplicate")) {
            context.structural { it.duplicateComponents(targets) }
            rowsRevision = -1
        }
        if (ImGui.menuItem("Delete")) {
            context.structural { it.deleteComponents(targets) }
            rowsRevision = -1
        }
        if (ImGui.menuItem("Copy subtree")) {
            context.clipboard = ComponentTemplates.extract(document, index, "clipboard", "Pasted subtree")
        }
        if (ImGui.menuItem("Paste into", "", false, context.clipboard != null)) {
            context.clipboard?.let { template -> context.structural { it.addTemplate(template, index) } }
            rowsRevision = -1
        }
        ImGui.separator()

        if (ImGui.menuItem("Move up")) {
            context.structural { it.move(index, -1) }
            rowsRevision = -1
        }
        if (ImGui.menuItem("Move down")) {
            context.structural { it.move(index, 1) }
            rowsRevision = -1
        }
        if (ImGui.menuItem("Move to root")) {
            context.structural { it.reparentAll(targets, InterfaceDocument.ROOT) }
            rowsRevision = -1
        }
        ImGui.separator()

        if (ImGui.menuItem(if (definition.hidden) "Show" else "Hide")) {
            val hide = !definition.hidden
            context.editSelection("Toggle hidden") { it.hidden = hide }
        }
        if (ImGui.menuItem("Isolate")) {
            isolate(document, index)
        }
        if (ImGui.menuItem("Show all")) {
            context.document?.let { current ->
                current.edit("Show all") { current.components.forEach { it.hidden = false } }
                context.touched()
            }
        }
        ImGui.separator()

        if (ImGui.menuItem("Bake live state")) {
            context.structural { it.bakeLiveState(targets) }
        }
        if (ImGui.menuItem("Copy hash")) {
            ImGui.setClipboardText("0x%08X".format(document.hash(index)))
        }
        if (ImGui.menuItem("Copy index")) {
            ImGui.setClipboardText(index.toString())
        }
        ImGui.endPopup()
    }

    private fun templateMenu(parent: Int) {
        for ((category, templates) in ComponentTemplates.byCategory) {
            if (!ImGui.beginMenu(category)) {
                continue
            }
            for (template in templates) {
                if (ImGui.menuItem(template.name)) {
                    context.structural { it.addTemplate(template, parent) }
                    rowsRevision = -1
                }
                if (ImGui.isItemHovered()) {
                    ImGuiText.tooltip((template.description))
                }
            }
            ImGui.endMenu()
        }
    }

    /** Hides everything outside a subtree, which is how a busy interface is read. */
    private fun isolate(document: InterfaceDocument, index: Int) {
        val keep = HashSet(document.subtree(index))
        var parent = document.component(index)?.parent ?: InterfaceDocument.ROOT
        var guard = document.componentCount
        while (parent >= 0 && guard-- > 0) {
            keep.add(parent)
            parent = document.component(parent)?.parent ?: InterfaceDocument.ROOT
        }
        document.edit("Isolate $index") {
            for (other in 0 until document.componentCount) {
                document.component(other)?.hidden = other !in keep
            }
        }
        context.touched()
    }

    // -------------------------------------------------------------- keyboard

    /**
     * Arrow-key navigation over the displayed rows. The viewer hands the frame's
     * presses over; they are only acted on while the tree has focus, so the same
     * keys still reach the interface when the stage does.
     */
    private fun handleKeys() {
        if (keys.isEmpty() || !ImGui.isWindowFocused()) {
            return
        }
        val position = rows.indexOfFirst { it.index == context.primary }
        for ((key, _) in keys) {
            when (key) {
                GLFW_KEY_UP -> rows.getOrNull(position - 1)?.let { context.reveal(it.index) }
                GLFW_KEY_DOWN -> rows.getOrNull(position + 1)?.let { context.reveal(it.index) }
                GLFW_KEY_LEFT -> collapseOrAscend()
                GLFW_KEY_RIGHT -> expandOrDescend()
            }
        }
    }

    private fun collapseOrAscend() {
        val index = context.primary
        if (index < 0) {
            return
        }
        val document = context.document ?: return
        if (document.children(index).isNotEmpty() && index !in collapsed) {
            collapsed.add(index)
            rowsRevision = -1
            return
        }
        document.component(index)?.parent?.takeIf { it >= 0 }?.let { context.reveal(it) }
    }

    private fun expandOrDescend() {
        val index = context.primary
        if (index < 0) {
            return
        }
        val document = context.document ?: return
        val children = document.children(index)
        if (children.isEmpty()) {
            return
        }
        if (collapsed.remove(index)) {
            rowsRevision = -1
            return
        }
        context.reveal(children.first())
    }

    companion object {
        private const val INDENT = 12f
        private const val LEAF_WIDTH = 15f
        private const val NAME_LIMIT = 30
        private const val DIM = 0.55f
        private const val DRAG_THRESHOLD = 4f
        private const val EDGE_ZONE = 0.3f

        private const val DROP_NONE = 0
        private const val DROP_BEFORE = 1
        private const val DROP_INSIDE = 2
        private const val DROP_AFTER = 3

        /** ImGui packs colours as ABGR. */
        private const val DROP_COLOUR = 0xff2fd0ffL.toInt()

        private val TAG = Regex("<[^>]*>")

        private fun typeTag(type: Int): String = when (type) {
            ComponentType.CONTAINER -> "LYR"
            ComponentType.TYPE_2 -> "UNC"
            ComponentType.FIGURE -> "RCT"
            ComponentType.TEXT -> "TXT"
            ComponentType.GRAPHIC -> "GFX"
            ComponentType.MODEL -> "MDL"
            ComponentType.LINE -> "LIN"
            else -> "T$type"
        }

        /** Type colour coding, so the shape of an interface is readable at a glance. */
        private fun typeColour(type: Int): FloatArray = when (type) {
            ComponentType.CONTAINER -> floatArrayOf(0.62f, 0.70f, 0.80f)
            ComponentType.TYPE_2 -> floatArrayOf(0.55f, 0.62f, 0.72f)
            ComponentType.FIGURE -> floatArrayOf(0.93f, 0.66f, 0.40f)
            ComponentType.TEXT -> floatArrayOf(0.55f, 0.88f, 0.60f)
            ComponentType.GRAPHIC -> floatArrayOf(0.45f, 0.82f, 0.95f)
            ComponentType.MODEL -> floatArrayOf(0.80f, 0.62f, 0.95f)
            ComponentType.LINE -> floatArrayOf(0.95f, 0.87f, 0.45f)
            else -> floatArrayOf(0.75f, 0.75f, 0.78f)
        }
    }
}
