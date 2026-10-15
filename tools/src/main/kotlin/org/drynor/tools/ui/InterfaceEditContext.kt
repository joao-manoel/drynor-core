package org.drynor.tools.ui

import org.drynor.tools.rsinterface.ComponentState
import org.drynor.tools.rsinterface.ComponentTemplate
import org.drynor.tools.rsinterface.InterfaceDocument
import org.drynor.tools.rsinterface.InterfaceInstance
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

/**
 * The one piece of state every editor panel needs: which document is open, which
 * components are selected, and how to change them.
 *
 * The panels are otherwise independent - the tree does not know the inspector
 * exists - so without something in the middle each of them would need the
 * document, the selection, the undo bookkeeping and a way to tell the viewer that
 * something changed. Threading four constructor parameters through six panels is
 * how a panel ends up quietly forgetting one of them; the point of this class is
 * that there is exactly one way to make an edit and it always does the
 * bookkeeping.
 *
 * ### Why every edit funnels through here
 *
 * [InterfaceDocument] separates *definition* state from the live
 * [ComponentState]s the renderer reads, and it re-seeds the states from the
 * definitions on the next `instance()` after any edit. Re-seeding throws away
 * everything a CS2 load script did - the graphics it set, the text it wrote - so
 * an edit that only changed a colour would blank out half the interface unless
 * the load scripts are re-run afterwards. [onEdit] is what the viewer hangs that
 * on, and it only fires reliably because nothing writes to the document directly.
 *
 * ### Selection and reindexing
 *
 * A component's array index is its identity (see [InterfaceDocument]), so a
 * structural edit renumbers everything after the insertion or deletion point.
 * The document remaps its own [InterfaceDocument.selected] across that, and
 * [structural] is written to hand it the selection first and read it back after,
 * which is why the selection follows the component rather than the slot. A
 * multiple selection cannot be remapped that way - there is only one slot to
 * carry through - so it collapses to the primary, which is honest about what
 * survived rather than leaving stale indices pointing at other components.
 */
class InterfaceEditContext {

    /** The open document, or null when nothing is open. */
    var document: InterfaceDocument? = null
        private set

    /**
     * The instance the renderer is drawing this frame. Set once per frame by the
     * viewer so every panel sees the same object the stage does; asking the
     * document for it again mid-frame could rebuild it underneath a panel.
     */
    var instance: InterfaceInstance? = null
        private set

    private val selected = LinkedHashSet<Int>()

    /** Every selected component index, in the order they were selected. */
    val selection: Set<Int> get() = selected

    /** The component the inspector edits; the last one selected, or -1. */
    var primary: Int = -1
        private set

    /**
     * Bumped whenever anything changed the document. Panels that cache a derived
     * view - the validation report, the tree's flattened rows - compare against
     * their own copy rather than recomputing every frame.
     */
    var revision: Int = 0
        private set

    /** Notified after every edit, before the next frame is built. */
    var onEdit: (() -> Unit)? = null

    /**
     * A component the tree and the inspector should scroll to and open up to,
     * set when a validation issue or a trace entry is clicked. Cleared by
     * whoever honours it.
     */
    var revealed: Int = -1

    /**
     * Copied subtree, held as a template because that is the shape
     * [InterfaceDocument.addTemplate] can paste back - it carries the internal
     * parent links, so pasting a window frame pastes its children too.
     */
    var clipboard: ComponentTemplate? = null

    // --- Lifecycle ---------------------------------------------------------

    fun attach(document: InterfaceDocument?) {
        this.document = document
        this.instance = null
        selected.clear()
        primary = -1
        revision++
    }

    /** Called once per frame by the viewer with the instance it is drawing. */
    fun frame(instance: InterfaceInstance?) {
        this.instance = instance
    }

    val open: Boolean get() = document != null

    val componentCount: Int get() = document?.componentCount ?: 0

    fun definition(index: Int): InterfaceComponentDefinition? = document?.component(index)

    fun state(index: Int): ComponentState? = instance?.state(index)

    /** The definition the inspector is editing, or null. */
    fun primaryDefinition(): InterfaceComponentDefinition? = definition(primary)

    fun primaryState(): ComponentState? = state(primary)

    // --- Selection ---------------------------------------------------------

    fun isSelected(index: Int): Boolean = index in selected

    /**
     * Selects [index]. [additive] toggles it into the existing selection, which
     * is the ctrl-click behaviour; without it the selection is replaced.
     */
    fun select(index: Int, additive: Boolean = false) {
        if (index < 0) {
            clearSelection()
            return
        }
        if (!additive) {
            selected.clear()
            selected.add(index)
            primary = index
        } else if (!selected.remove(index)) {
            selected.add(index)
            primary = index
        } else {
            primary = selected.lastOrNull() ?: -1
        }
        document?.selected = primary
    }

    /** Replaces the selection wholesale; used by shift-click and by "select all". */
    fun setSelection(indices: Collection<Int>, primaryIndex: Int = indices.lastOrNull() ?: -1) {
        selected.clear()
        selected.addAll(indices.filter { it >= 0 })
        primary = if (primaryIndex in selected) primaryIndex else selected.lastOrNull() ?: -1
        document?.selected = primary
    }

    fun clearSelection() {
        selected.clear()
        primary = -1
        document?.selected = -1
    }

    /** The selection, or just the primary when nothing is explicitly selected. */
    fun targets(): List<Int> = when {
        selected.isNotEmpty() -> selected.toList()
        primary >= 0 -> listOf(primary)
        else -> emptyList()
    }

    fun reveal(index: Int) {
        if (index < 0) {
            return
        }
        revealed = index
        select(index)
    }

    // --- Edits -------------------------------------------------------------

    /**
     * Edits the primary component as one undo step.
     *
     * [coalesceKey] should include the field and the component, e.g.
     * `"x@$index"`, so dragging one slider collapses into a single step without
     * swallowing the edit before it.
     */
    fun editPrimary(
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Boolean {
        val current = document ?: return false
        if (!current.editComponent(primary, label, coalesceKey, block)) {
            return false
        }
        touched()
        return true
    }

    /** [editPrimary] applied to the whole selection as one step. */
    fun editSelection(
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Int {
        val current = document ?: return 0
        val changed = current.editComponents(targets(), label, coalesceKey, block)
        if (changed > 0) {
            touched()
        }
        return changed
    }

    /**
     * Runs a structural edit - anything that inserts, removes or moves - and
     * carries the selection through the renumbering it causes.
     */
    fun structural(block: (InterfaceDocument) -> Unit) {
        val current = document ?: return
        current.selected = primary
        block(current)
        val landed = current.selected
        setSelection(if (landed >= 0) listOf(landed) else emptyList(), landed)
        touched()
    }

    /**
     * Closes a coalescing gesture. Call on slider or drag release so the next
     * edit starts a new undo step.
     */
    fun endEdit() {
        document?.endEdit()
    }

    fun undo() {
        val current = document ?: return
        if (current.undo()) {
            afterHistory(current)
        }
    }

    fun redo() {
        val current = document ?: return
        if (current.redo()) {
            afterHistory(current)
        }
    }

    private fun afterHistory(current: InterfaceDocument) {
        val landed = current.selected
        setSelection(if (landed >= 0) listOf(landed) else emptyList(), landed)
        touched()
    }

    /** Marks the document changed without making an edit of its own. */
    fun touched() {
        revision++
        onEdit?.invoke()
    }
}
