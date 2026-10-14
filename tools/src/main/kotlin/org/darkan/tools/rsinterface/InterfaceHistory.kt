package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

/**
 * One undoable step: the component array as it looked *before* the edit that
 * produced this entry, plus the selection at that moment.
 *
 * [timestamp] is refreshed while an edit keeps coalescing, so a slider held down
 * for ten seconds still collapses into a single step.
 */
class HistoryEntry(
    /** Short label for the UI, e.g. "Add Graphic" or "Delete 3 components". */
    val label: String,
    /**
     * Identity of the continuous gesture that produced this step, or null for a
     * discrete edit. Two consecutive edits with the same key inside the coalesce
     * window collapse into one entry.
     */
    val coalesceKey: String?,
    /** Deep copy of the component array before the edit. */
    val components: Array<InterfaceComponentDefinition>,
    /** Selected component index before the edit, or -1. */
    val selected: Int,
    internal var timestamp: Long
)

/**
 * Bounded undo/redo stack for [InterfaceDocument].
 *
 * ### Why snapshots
 *
 * Component index *is* the cache file number and the low 16 bits of every
 * component hash, so a structural edit rewrites `parent`, `id` and hook
 * arguments across the whole array. An inverse-operation ("command") history
 * would have to re-derive all of that backwards for every edit kind; a snapshot
 * of the array is both smaller to reason about and impossible to get subtly
 * wrong. The cost is one deep copy of the array per undoable step: measured at
 * 0.40 ms and 0.51 MB retained for a 1000 component interface carrying options,
 * strings and two hooks each, so the default 64 step limit tops out around 33 MB
 * for an interface that large and well under 1 MB for a typical 30 component
 * one. That is cheap per edit but not cheap sixty times a second, which is why
 * continuous edits coalesce rather than snapshotting per frame.
 *
 * The history never holds references into the live document: [HistoryEntry.components]
 * is always produced by [ComponentDefinitions.deepCopy].
 */
class InterfaceHistory(
    /** Maximum number of undo steps kept; the oldest are dropped past this. */
    val limit: Int = DEFAULT_LIMIT,
    /**
     * How long after a coalescing edit a following edit with the same key still
     * joins it. Long enough to cover a dropped frame, short enough that a pause
     * and a second drag are separate steps.
     */
    val coalesceWindowMillis: Long = DEFAULT_COALESCE_WINDOW
) {

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    /** Swappable clock so coalescing can be tested without sleeping. */
    var clock: () -> Long = { System.currentTimeMillis() }

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Label of the step [undo] would revert, for an "Undo <label>" menu item. */
    val undoLabel: String? get() = undoStack.lastOrNull()?.label

    /** Label of the step [redo] would reapply. */
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /** Undo labels oldest first, for a history list panel. */
    val undoLabels: List<String> get() = undoStack.map { it.label }

    /** Redo labels, next to reapply last. */
    val redoLabels: List<String> get() = redoStack.map { it.label }

    val size: Int get() = undoStack.size

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /**
     * Records the pre-edit state of a document.
     *
     * [snapshot] is a lambda so that a coalesced edit costs nothing: the deep copy
     * is only taken when a new step is actually opened.
     *
     * @return true when a new undo step was opened, false when the edit joined the
     *   previous one.
     */
    fun record(
        label: String,
        coalesceKey: String?,
        selected: Int,
        snapshot: () -> Array<InterfaceComponentDefinition>
    ): Boolean {
        val now = clock()
        val top = undoStack.lastOrNull()
        if (coalesceKey != null && top != null && top.coalesceKey == coalesceKey &&
            now - top.timestamp <= coalesceWindowMillis
        ) {
            // The state captured when the gesture started is the one to return to,
            // so the existing entry is kept as is and only its deadline moves.
            top.timestamp = now
            return false
        }
        redoStack.clear()
        undoStack.addLast(HistoryEntry(label, coalesceKey, snapshot(), selected, now))
        while (undoStack.size > limit) {
            undoStack.removeFirst()
        }
        return true
    }

    /**
     * Ends any coalescing gesture, so the next edit always opens a new step.
     * Call this on mouse release, or when the UI focus moves to another field.
     */
    fun endCoalescing() {
        undoStack.lastOrNull()?.timestamp = Long.MIN_VALUE / 2
    }

    /**
     * Pops the newest undo step, pushing [current] onto the redo stack under the
     * same label.
     *
     * @return the state to restore, or null when there is nothing to undo.
     */
    fun undo(selected: Int, current: () -> Array<InterfaceComponentDefinition>): HistoryEntry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(HistoryEntry(entry.label, null, current(), selected, clock()))
        return entry
    }

    /** The mirror of [undo]. */
    fun redo(selected: Int, current: () -> Array<InterfaceComponentDefinition>): HistoryEntry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(HistoryEntry(entry.label, null, current(), selected, clock()))
        return entry
    }

    companion object {
        const val DEFAULT_LIMIT = 64
        const val DEFAULT_COALESCE_WINDOW = 600L
    }
}
