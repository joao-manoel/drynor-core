package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import java.util.Collections

/** Sentinel for "this slot's parent is supplied by the other field". */
private const val UNSET = Int.MIN_VALUE

/**
 * The editable model of one interface: an ordered component array plus undo,
 * validation, and a lazily rebuilt [InterfaceInstance] for the renderer.
 *
 * ```
 * cache index 3  --decode-->  InterfaceDocument  --instance()-->  InterfaceInstance
 *      ^                        (definitions)                      (render state)
 *      |                             |
 *      +--------- InterfaceStore.save ---------+
 * ```
 *
 * ### Definition state versus render state
 *
 * A document owns `InterfaceComponentDefinition`s. [InterfaceInstance] owns
 * [ComponentState]s, which are *seeded* from those definitions and then diverge -
 * a CS2 script or the editor's live property panel writes render state, never the
 * definition. That means:
 *
 * - Editing a definition through [edit] / [editComponent] and then asking for
 *   [instance] re-seeds the states, so the change is visible.
 * - Editing a [ComponentState] directly is invisible to the document and is
 *   **lost** the next time the instance is re-seeded or rebuilt. [bakeLiveState]
 *   promotes live state back into the definitions, which is the "tweak live, then
 *   commit" workflow.
 *
 * ### Reindexing
 *
 * A component's array position is its cache file number, the low 16 bits of its
 * `id`, the value its children hold in `parent`, and the low 16 bits of every CS2
 * component hash aimed at it. Nothing indirects any of that, so every insert,
 * delete and move shifts a range of components and has to rewrite all four. Every
 * structural method here funnels through one private routine that:
 *
 * 1. rewrites `id` to `index or (interfaceId shl 16)` for every component,
 * 2. rewrites `parent` through the old-index to new-index map, and drops a parent
 *    that no longer exists back to [ROOT],
 * 3. rewrites integer hook arguments that look like a component hash of *this*
 *    interface (see [ComponentDefinitions.isComponentHash]) to the new hash, and
 *    rewrites a hash whose target was deleted to -1, the client's "no component"
 *    value, recording each of those in [lastReindexIssues],
 * 4. remaps [selected] so the selection follows the component it was on.
 *
 * Two classes of reference are deliberately **not** remapped, because they cannot
 * be: component hashes belonging to *other* interfaces (an interface this
 * document has never seen may hold `thisInterface shl 16 or index` in a script
 * argument, and nothing enumerates those), and every hash in compiled CS2
 * bytecode. Renumbering an interface that other content already references is a
 * breaking change no matter how careful the editor is; append rather than insert
 * when that matters. When [id] is 0 hash remapping is disabled entirely, since
 * every small integer argument would look like a component hash of interface 0.
 */
class InterfaceDocument(
    /** Interface id; also the archive number in cache index 3. */
    val id: Int,
    components: List<InterfaceComponentDefinition> = emptyList(),
    /** Undo stack; injectable so a caller can widen the limit or share a clock. */
    val history: InterfaceHistory = InterfaceHistory()
) {

    private val items = ArrayList<InterfaceComponentDefinition>(components)
    private val view: List<InterfaceComponentDefinition> = Collections.unmodifiableList(items)

    private var cachedInstance: InterfaceInstance? = null
    private var structureDirty = true
    private var statesDirty = false

    /** Whether hook argument hashes can be remapped at all; see the class doc. */
    private val remapHashes = id > 0

    /** True when there are edits that have not been persisted by [InterfaceStore]. */
    var dirty: Boolean = false
        private set

    /**
     * Component the UI has selected, or -1. Structural edits move this so it keeps
     * pointing at the same component, or clear it when that component is gone.
     */
    var selected: Int = -1
        set(value) {
            field = if (value in items.indices) value else -1
        }

    /** Invoked whenever a structural edit produced a brand new [InterfaceInstance]. */
    var onRebuild: ((InterfaceInstance) -> Unit)? = null

    /**
     * References the last structural edit could not preserve, cleared at the start
     * of every edit. Surface these; they are silent data loss otherwise.
     */
    var lastReindexIssues: List<InterfaceIssue> = emptyList()
        private set

    init {
        // The invariant is enforced up front so a document imported from JSON, or
        // built by hand, is indistinguishable from one decoded out of the cache.
        for (index in items.indices) {
            items[index].id = ComponentDefinitions.pack(id, index)
        }
    }

    val componentCount: Int get() = items.size

    /** Components in cache order, which is also draw order. Read only. */
    val components: List<InterfaceComponentDefinition> get() = view

    fun component(index: Int): InterfaceComponentDefinition? = items.getOrNull(index)

    /** The packed `interfaceId shl 16 or index` hash for a component. */
    fun hash(index: Int): Int = ComponentDefinitions.pack(id, index)

    // --- Tree queries ------------------------------------------------------

    /** Child indices of [parentIndex] in draw order; pass [ROOT] for the top level. */
    fun children(parentIndex: Int): List<Int> = items.indices.filter { items[it].parent == parentIndex }

    /**
     * [index] plus every descendant, in ascending array order. Cycle safe: each
     * component is visited at most once.
     */
    fun subtree(index: Int): List<Int> {
        if (index !in items.indices) {
            return emptyList()
        }
        return collectSubtree(index, childMap()).sorted()
    }

    /** How many parents deep [index] sits, or -1 when it is not a valid index. */
    fun depth(index: Int): Int {
        if (index !in items.indices) {
            return -1
        }
        var depth = 0
        var current = items[index].parent
        var guard = items.size
        while (current in items.indices && guard-- > 0) {
            depth++
            current = items[current].parent
        }
        return depth
    }

    // --- Instance ----------------------------------------------------------

    /**
     * The instance the renderer should draw, rebuilt when the structure changed
     * and re-seeded when only definition fields changed. Cheap to call per frame.
     */
    fun instance(): InterfaceInstance {
        var current = cachedInstance
        if (current == null || structureDirty) {
            current = InterfaceInstance(id, InterfaceDefinition(id, items.toTypedArray()))
            cachedInstance = current
            structureDirty = false
            statesDirty = false
            onRebuild?.invoke(current)
        } else if (statesDirty) {
            // Same ComponentState objects, re-seeded from the definitions they were
            // built from, so nothing holding on to the instance needs to know.
            current.reset()
            statesDirty = false
        }
        return current
    }

    /** The `InterfaceDefinition` backing the current [instance]. */
    fun definition(): InterfaceDefinition = instance().definition

    /** Forces the next [instance] call to re-seed every [ComponentState]. */
    fun invalidateStates() {
        statesDirty = true
    }

    /** Forces the next [instance] call to build a whole new instance. */
    fun rebuild() {
        structureDirty = true
    }

    // --- History -----------------------------------------------------------

    val canUndo: Boolean get() = history.canUndo

    val canRedo: Boolean get() = history.canRedo

    val undoLabel: String? get() = history.undoLabel

    val redoLabel: String? get() = history.redoLabel

    /** Deep copy of the component array, the unit the history stores. */
    fun snapshot(): Array<InterfaceComponentDefinition> =
        Array(items.size) { ComponentDefinitions.deepCopy(items[it]) }

    fun undo(): Boolean {
        val entry = history.undo(selected, ::snapshot) ?: return false
        restore(entry)
        return true
    }

    fun redo(): Boolean {
        val entry = history.redo(selected, ::snapshot) ?: return false
        restore(entry)
        return true
    }

    fun clearHistory() {
        history.clear()
    }

    /**
     * Closes any coalescing gesture, so the next edit opens a new undo step.
     * Call on mouse release or when an input loses focus.
     */
    fun endEdit() {
        history.endCoalescing()
    }

    private fun restore(entry: HistoryEntry) {
        items.clear()
        items.addAll(entry.components)
        for (index in items.indices) {
            items[index].id = ComponentDefinitions.pack(id, index)
        }
        selected = entry.selected
        structureDirty = true
        dirty = true
    }

    /** Marks the document as matching what is on disk again. */
    fun markSaved() {
        dirty = false
    }

    // --- Property edits ----------------------------------------------------

    /**
     * Runs [block] as one undoable step.
     *
     * Everything the UI writes to a definition should go through here, whatever
     * the field: there is intentionally no per-field setter, because
     * `InterfaceComponentDefinition` has around a hundred fields and a property
     * panel needs all of them.
     *
     * @param label what the step is called in the undo menu.
     * @param coalesceKey identity of a continuous gesture; consecutive edits
     *   sharing a key inside [InterfaceHistory.coalesceWindowMillis] collapse into
     *   a single undo step. Include the component index, e.g. `"pos@$index"`, so
     *   dragging one component does not swallow the previous edit to another.
     *
     * Structural fields are allowed too: `parent` changes are detected and trigger
     * an instance rebuild. Note that changing `parent` this way does **not**
     * reposition the component in the array, so use [reparent] when draw order
     * matters.
     */
    fun <T> edit(label: String, coalesceKey: String? = null, block: () -> T): T {
        beginEdit(label, coalesceKey)
        val parentsBefore = IntArray(items.size) { items[it].parent }
        val result = block()
        var structural = false
        for (index in items.indices) {
            if (items[index].parent != parentsBefore[index]) {
                structural = true
                break
            }
        }
        if (structural) {
            structureDirty = true
        } else {
            statesDirty = true
        }
        return result
    }

    /** [edit] scoped to one component; a no-op returning false when out of range. */
    fun editComponent(
        index: Int,
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Boolean {
        val component = items.getOrNull(index) ?: return false
        edit(label, coalesceKey) { block(component) }
        return true
    }

    /** [edit] applied to several components as one step; returns how many were hit. */
    fun editComponents(
        indices: Collection<Int>,
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Int {
        val targets = indices.filter { it in items.indices }
        if (targets.isEmpty()) {
            return 0
        }
        edit(label, coalesceKey) { targets.forEach { block(items[it]) } }
        return targets.size
    }

    private fun beginEdit(label: String, coalesceKey: String? = null) {
        lastReindexIssues = emptyList()
        history.record(label, coalesceKey, selected, ::snapshot)
        dirty = true
    }

    // --- Structural edits --------------------------------------------------

    /**
     * Inserts a new component of [type] with default fields.
     *
     * @param parentIndex the container to attach to, or [ROOT] for the top level.
     * @param insertAt position among that parent's children, or -1 to append.
     * @return the new component's index, or -1 when [parentIndex] is invalid or the
     *   interface is already at [ComponentDefinitions.MAX_COMPONENTS].
     */
    fun addComponent(type: Int, parentIndex: Int = ROOT, insertAt: Int = -1): Int =
        addComponent(ComponentTemplates.defaults(type), parentIndex, insertAt)

    /**
     * Inserts [definition] as a new component; the id and parent are overwritten to
     * keep the invariant, everything else is used as given.
     */
    fun addComponent(
        definition: InterfaceComponentDefinition,
        parentIndex: Int = ROOT,
        insertAt: Int = -1
    ): Int {
        if (!validParent(parentIndex) || items.size >= ComponentDefinitions.MAX_COMPONENTS) {
            return -1
        }
        val label = "Add ${ComponentType.name(definition.type)}"
        beginEdit(label)
        val position = insertionPosition(parentIndex, insertAt)
        val slots = ArrayList<Slot>(items.size + 1)
        for (index in 0 until position) {
            slots.add(Slot(index))
        }
        slots.add(Slot(-1, definition, parentOld = parentIndex))
        for (index in position until items.size) {
            slots.add(Slot(index))
        }
        applySlots(slots)
        selected = position
        return position
    }

    /**
     * Instantiates a [ComponentTemplate], which may produce a small tree.
     *
     * @return the new indices in template order; the template's own root is first.
     */
    fun addTemplate(
        template: ComponentTemplate,
        parentIndex: Int = ROOT,
        insertAt: Int = -1
    ): IntArray {
        if (!validParent(parentIndex)) {
            return IntArray(0)
        }
        val created = template.create()
        if (created.isEmpty() || items.size + created.size > ComponentDefinitions.MAX_COMPONENTS) {
            return IntArray(0)
        }
        beginEdit("Add ${template.name}")
        val position = insertionPosition(parentIndex, insertAt)
        val slots = ArrayList<Slot>(items.size + created.size)
        for (index in 0 until position) {
            slots.add(Slot(index))
        }
        for (component in created) {
            val slot = Slot(-1, component.definition)
            if (component.parent == ComponentTemplates.ATTACH) {
                slot.parentOld = parentIndex
            } else {
                slot.parentSlot = position + component.parent
            }
            slots.add(slot)
        }
        for (index in position until items.size) {
            slots.add(Slot(index))
        }
        applySlots(slots)
        selected = position
        return IntArray(created.size) { position + it }
    }

    /** Deletes [index] and everything under it. */
    fun deleteComponent(index: Int): Boolean = deleteComponents(listOf(index)) > 0

    /**
     * Deletes several subtrees as one undoable step.
     *
     * Descendants are always removed with their parent, so a component can never be
     * left pointing at a hole. The selection falls back to the nearest surviving
     * ancestor of whatever was selected.
     *
     * @return how many components were removed in total.
     */
    fun deleteComponents(indices: Collection<Int>): Int {
        val roots = topLevel(indices)
        if (roots.isEmpty()) {
            return 0
        }
        val map = childMap()
        val removed = HashSet<Int>()
        for (root in roots) {
            removed.addAll(collectSubtree(root, map))
        }
        val count = removed.size
        val label = if (count == 1) {
            "Delete ${ComponentType.name(items[roots[0]].type)}"
        } else {
            "Delete $count components"
        }
        beginEdit(label)
        var fallback = selected
        while (fallback in items.indices && fallback in removed) {
            fallback = items[fallback].parent
        }
        val slots = ArrayList<Slot>(items.size - count)
        for (index in items.indices) {
            if (index !in removed) {
                slots.add(Slot(index))
            }
        }
        applySlots(slots, selectionSource = fallback)
        return count
    }

    /** Deep copies [index] and its subtree, inserting the copy after the original. */
    fun duplicateComponent(index: Int): Int = duplicateComponents(listOf(index)).firstOrNull() ?: -1

    /**
     * Deep copies several subtrees as one step. Each copy is inserted immediately
     * after its source subtree.
     *
     * Hook arguments inside a copy that pointed at another component of the same
     * source subtree are repointed at the corresponding copy, so a duplicated
     * widget wires itself up rather than driving the original. Arguments pointing
     * outside the subtree are left aimed where they were.
     *
     * @return the index of each copy's root, in source order.
     */
    fun duplicateComponents(indices: Collection<Int>): IntArray {
        val roots = topLevel(indices)
        if (roots.isEmpty()) {
            return IntArray(0)
        }
        val map = childMap()
        val groups = roots.map { root ->
            val order = orderedSubtree(root, map)
            DuplicateGroup(order, (collectSubtree(root, map).max()) + 1, IntArray(order.size))
        }
        val total = groups.sumOf { it.order.size }
        if (items.size + total > ComponentDefinitions.MAX_COMPONENTS) {
            return IntArray(0)
        }
        val label = if (roots.size == 1) {
            "Duplicate ${ComponentType.name(items[roots[0]].type)}"
        } else {
            "Duplicate ${roots.size} components"
        }
        beginEdit(label)
        val byPosition = groups.groupBy { it.position }
        val slots = ArrayList<Slot>(items.size + total)
        for (position in 0..items.size) {
            byPosition[position]?.forEach { group ->
                group.order.forEachIndexed { offset, node ->
                    group.slots[offset] = slots.size
                    slots.add(Slot(-1, ComponentDefinitions.deepCopy(items[node])))
                }
            }
            if (position < items.size) {
                slots.add(Slot(position))
            }
        }
        for (group in groups) {
            val positions = HashMap<Int, Int>(group.order.size)
            group.order.forEachIndexed { offset, node -> positions[node] = offset }
            group.order.forEachIndexed { offset, node ->
                val slot = slots[group.slots[offset]]
                val parent = items[node].parent
                val within = if (offset == 0) null else positions[parent]
                if (within == null) {
                    slot.parentOld = parent
                } else {
                    slot.parentSlot = group.slots[within]
                }
            }
        }
        val oldToNew = applySlots(slots, selectionSource = roots[0])
        repointCopies(groups, oldToNew)
        selected = groups[0].slots[0]
        return IntArray(groups.size) { groups[it].slots[0] }
    }

    /**
     * Moves [index] and its subtree under [newParent] at sibling position
     * [insertAt] (-1 appends).
     *
     * The subtree becomes a contiguous block in the array, which means components
     * between the old and the new location are renumbered.
     */
    fun reparent(index: Int, newParent: Int, insertAt: Int = -1): Boolean {
        if (index !in items.indices) {
            return false
        }
        val label = if (newParent == items[index].parent) "Reorder component" else "Reparent component"
        return moveSubtrees(listOf(index), newParent, insertAt, label) > 0
    }

    /**
     * [reparent] applied to several subtrees at once, keeping their relative order.
     * Doing it in one pass matters: each move renumbers the array, so moving them
     * one at a time would leave the caller's remaining indices stale.
     */
    fun reparentAll(indices: Collection<Int>, newParent: Int, insertAt: Int = -1): Int {
        val roots = topLevel(indices)
        if (roots.isEmpty()) {
            return 0
        }
        val label = if (roots.size == 1) "Reparent component" else "Reparent ${roots.size} components"
        return moveSubtrees(roots, newParent, insertAt, label)
    }

    /**
     * Lifts [roots] and their subtrees out of the array and reinserts them as one
     * contiguous block under [newParent].
     *
     * @return how many subtrees moved, 0 when the move was rejected.
     */
    private fun moveSubtrees(roots: List<Int>, newParent: Int, insertAt: Int, label: String): Int {
        if (roots.isEmpty() || !validParent(newParent)) {
            return 0
        }
        val map = childMap()
        val moving = HashSet<Int>()
        val order = ArrayList<Int>()
        for (root in roots) {
            order.addAll(orderedSubtree(root, map))
            moving.addAll(collectSubtree(root, map))
        }
        if (newParent in moving) {
            // The new parent is inside what is being moved, which would detach the
            // whole block from the root and build a parent cycle.
            return 0
        }
        val remaining = items.indices.filter { it !in moving }
        val position = insertionPositionAmong(remaining, moving, newParent, insertAt, map)
        beginEdit(label)
        for (root in roots) {
            items[root].parent = newParent
        }
        val slots = ArrayList<Slot>(items.size)
        for (offset in 0..remaining.size) {
            if (offset == position) {
                order.forEach { slots.add(Slot(it)) }
            }
            if (offset < remaining.size) {
                slots.add(Slot(remaining[offset]))
            }
        }
        applySlots(slots)
        return roots.size
    }

    /** Shifts [index] within its sibling group, negative moves it earlier. */
    fun move(index: Int, delta: Int): Boolean {
        if (index !in items.indices || delta == 0) {
            return false
        }
        val parent = items[index].parent
        val siblings = children(parent)
        val current = siblings.indexOf(index)
        val target = (current + delta).coerceIn(0, siblings.lastIndex)
        if (target == current) {
            return false
        }
        return reorder(index, target)
    }

    /**
     * Moves [index] to [toPosition] within its sibling group, where position is
     * counted over the parent's children in draw order.
     */
    fun reorder(index: Int, toPosition: Int): Boolean {
        if (index !in items.indices) {
            return false
        }
        return reparent(index, items[index].parent, toPosition)
    }

    /**
     * Changes a component's type.
     *
     * Type decides which block of fields the cache stores, so switching type
     * silently drops whatever the old type owned on the next save.
     *
     * @param applyDefaults also resets the new type's own fields to the template
     *   defaults, keeping geometry, parent, name, options and hooks.
     */
    fun setType(index: Int, type: Int, applyDefaults: Boolean = false): Boolean {
        val component = items.getOrNull(index) ?: return false
        if (component.type == type) {
            return false
        }
        return editComponent(index, "Set type ${ComponentType.name(type)}") { definition ->
            definition.type = type
            if (applyDefaults) {
                applyTypeDefaults(definition, type)
            }
        }
    }

    // --- Live state -------------------------------------------------------

    /**
     * Writes the live [ComponentState] values back into the definitions, so a
     * session of dragging and recolouring in the property panel becomes something
     * [InterfaceStore] can save.
     *
     * Exactly the fields [ComponentState] seeds from the definition are written
     * back. State that has no definition home is **not** baked, because the cache
     * has nowhere to put it: `aspectX`/`aspectY` (the client defaults them to 1:1
     * and only CS2 changes them), `scrollX`/`scrollY`, `itemId`/`itemAmount`, and
     * the computed box model (`x`, `y`, `width`, `height`, `screenX`, `screenY`).
     *
     * The interaction fields [ComponentState] grew for the runtime - the event
     * bitfield, the option array, the option base and the continue text - are not
     * baked yet either, deliberately, while their names settle. They map onto
     * `setting.setting`, `options`, `name`, `optionOverride`, `useOption` and
     * `disableHover` when that is worth wiring up.
     *
     * @param indices components to bake, or null for all of them.
     * @return how many components were written.
     */
    fun bakeLiveState(indices: Collection<Int>? = null): Int {
        val current = cachedInstance ?: return 0
        val targets = (indices ?: items.indices).filter { it in items.indices }
        if (targets.isEmpty()) {
            return 0
        }
        val label = if (targets.size == 1) "Bake live state" else "Bake live state (${targets.size})"
        edit(label) {
            for (index in targets) {
                val state = current.state(index) ?: continue
                bake(state, items[index])
            }
        }
        return targets.size
    }

    private fun bake(state: ComponentState, definition: InterfaceComponentDefinition) {
        definition.basePositionX = state.basePositionX
        definition.basePositionY = state.basePositionY
        definition.baseWidth = state.baseWidth
        definition.baseHeight = state.baseHeight
        definition.horizontalSizeMode = state.horizontalSizeMode.toByte()
        definition.verticalSizeMode = state.verticalSizeMode.toByte()
        definition.horizontalPositionMode = state.horizontalPositionMode.toByte()
        definition.verticalPositionMode = state.verticalPositionMode.toByte()
        definition.scrollWidth = state.scrollWidth
        definition.scrollHeight = state.scrollHeight
        definition.hidden = state.hidden
        definition.colour = state.colour
        definition.alpha = state.transparency
        definition.filled = state.filled
        definition.text = state.text
        definition.fontId = state.fontId
        definition.horizontalTextAlign = state.horizontalTextAlign
        definition.verticalTextAlign = state.verticalTextAlign
        definition.lineHeight = state.lineSpacing
        definition.lineCount = state.maxTextLines
        definition.shaded = state.shadow
        definition.defaultImage = state.graphicId
        definition.imageRotation = state.graphicAngle
        definition.aBoolean4861 = state.tiling
        definition.flipVertical = state.flipVertical
        definition.flipHorizontal = state.flipHorizontal
        definition.rotation = state.borderThickness
        definition.backgroundColour = state.graphicShadow
        definition.defaultMediaType = state.mediaType
        definition.defaultMediaId = state.mediaId
        definition.animation = state.animation
        definition.viewportX = state.modelOriginX
        definition.viewportY = state.modelOriginY
        definition.viewportZ = state.modelOriginZ
        definition.graphicPitch = state.modelPitch
        definition.graphicRoll = state.modelRoll
        definition.graphicYaw = state.modelYaw
        definition.graphicScale = state.modelScale
        definition.viewportWidth = state.modelAspectWidth
        definition.viewportHeight = state.modelAspectHeight
        definition.lineWidth = state.lineWidth
        definition.lineMirrored = state.lineMirrored
    }

    // --- Validation --------------------------------------------------------

    /** Developer facing warnings; see [InterfaceValidator] for the rule set. */
    fun validate(assets: InterfaceAssets = InterfaceAssets.NONE): List<InterfaceIssue> =
        InterfaceValidator.validate(id, items, assets)

    // --- Copying ----------------------------------------------------------

    /**
     * An independent document holding the same components under a new interface id.
     *
     * Hook arguments that were component hashes of this interface are repacked onto
     * [newId], so a copied interface drives its own components. Backs
     * [InterfaceStore.saveAs].
     */
    fun copyAs(newId: Int): InterfaceDocument {
        val copies = List(items.size) { ComponentDefinitions.deepCopy(items[it]) }
        val count = copies.size
        if (remapHashes && newId > 0) {
            for (copy in copies) {
                ComponentDefinitions.remapHookArgs(copy) { value ->
                    if (ComponentDefinitions.isComponentHash(value, id, count)) {
                        ComponentDefinitions.pack(newId, ComponentDefinitions.indexOf(value))
                    } else {
                        null
                    }
                }
            }
        }
        return InterfaceDocument(newId, copies)
    }

    // --- Reindexing internals ---------------------------------------------

    /**
     * One position in the new component array: either an existing component, moved
     * from [source], or a freshly created one.
     *
     * A created component's parent is expressed either as an old index
     * ([parentOld], remapped like everybody else) or as a position in the new array
     * ([parentSlot]), which is what lets a template or a duplicated subtree point
     * at components that do not exist yet.
     */
    private class Slot(
        val source: Int,
        val created: InterfaceComponentDefinition? = null,
        var parentOld: Int = UNSET,
        var parentSlot: Int = UNSET
    )

    private class DuplicateGroup(
        /** Source components in pre-order; the subtree root first. */
        val order: List<Int>,
        /** Array position the copies are inserted at. */
        val position: Int,
        /** New index of each copy, filled in while the slot list is built. */
        val slots: IntArray
    )

    /**
     * Rewrites the component array to the ordering described by [slots] and repairs
     * every index that encodes a component position. This is the only place the
     * array is reordered.
     *
     * @param selectionSource the old index the selection should follow, or -1.
     * @return the old index to new index map; -1 for components that were removed.
     */
    private fun applySlots(slots: List<Slot>, selectionSource: Int = selected): IntArray {
        val previousCount = items.size
        val oldToNew = IntArray(previousCount) { -1 }
        slots.forEachIndexed { index, slot ->
            if (slot.source >= 0) {
                oldToNew[slot.source] = index
            }
        }
        val next = ArrayList<InterfaceComponentDefinition>(slots.size)
        for (slot in slots) {
            next.add(slot.created ?: items[slot.source])
        }
        val issues = ArrayList<InterfaceIssue>()
        next.forEachIndexed { index, definition ->
            val slot = slots[index]
            definition.parent = when {
                slot.source >= 0 -> mapParent(definition.parent, oldToNew, index, issues)
                slot.parentSlot != UNSET -> slot.parentSlot
                else -> mapParent(slot.parentOld, oldToNew, index, issues)
            }
            definition.id = ComponentDefinitions.pack(id, index)
        }
        if (remapHashes) {
            next.forEachIndexed { index, definition ->
                ComponentDefinitions.remapHookArgs(definition) { value ->
                    if (!ComponentDefinitions.isComponentHash(value, id, previousCount)) {
                        return@remapHookArgs null
                    }
                    val target = oldToNew[ComponentDefinitions.indexOf(value)]
                    if (target >= 0) {
                        ComponentDefinitions.pack(id, target)
                    } else {
                        issues.add(
                            InterfaceIssue(
                                IssueSeverity.WARNING, index, "HOOK_ARG_CLEARED",
                                "a hook argument pointed at deleted component " +
                                    "${ComponentDefinitions.indexOf(value)} and was set to -1"
                            )
                        )
                        -1
                    }
                }
            }
        }
        items.clear()
        items.addAll(next)
        lastReindexIssues = issues
        selected = if (selectionSource in oldToNew.indices) oldToNew[selectionSource] else -1
        structureDirty = true
        dirty = true
        return oldToNew
    }

    private fun mapParent(
        parent: Int,
        oldToNew: IntArray,
        index: Int,
        issues: MutableList<InterfaceIssue>
    ): Int {
        if (parent < 0 || parent >= oldToNew.size) {
            return ROOT
        }
        val mapped = oldToNew[parent]
        if (mapped >= 0) {
            return mapped
        }
        issues.add(
            InterfaceIssue(
                IssueSeverity.WARNING, index, "PARENT_CLEARED",
                "parent $parent no longer exists; the component was moved to the top level"
            )
        )
        return ROOT
    }

    /**
     * Repoints hook arguments inside freshly made copies from the originals to the
     * copies. Runs after [applySlots], so both sides are already in new indices.
     */
    private fun repointCopies(groups: List<DuplicateGroup>, oldToNew: IntArray) {
        if (!remapHashes) {
            return
        }
        val count = items.size
        for (group in groups) {
            val originals = HashMap<Int, Int>(group.order.size)
            group.order.forEachIndexed { offset, node -> originals[oldToNew[node]] = group.slots[offset] }
            for (slot in group.slots) {
                ComponentDefinitions.remapHookArgs(items[slot]) { value ->
                    if (!ComponentDefinitions.isComponentHash(value, id, count)) {
                        return@remapHookArgs null
                    }
                    originals[ComponentDefinitions.indexOf(value)]?.let { ComponentDefinitions.pack(id, it) }
                }
            }
        }
    }

    /** Child indices per parent, built once for the tree walks below. */
    private fun childMap(): Map<Int, List<Int>> = items.indices.groupBy { items[it].parent }

    private fun collectSubtree(index: Int, map: Map<Int, List<Int>>): List<Int> {
        val result = ArrayList<Int>()
        val seen = BooleanArray(items.size)
        val queue = ArrayDeque<Int>()
        queue.addLast(index)
        seen[index] = true
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            for (child in map[current] ?: emptyList()) {
                if (!seen[child]) {
                    seen[child] = true
                    queue.addLast(child)
                }
            }
        }
        return result
    }

    /** [index] and its descendants in pre-order, so a parent always precedes a child. */
    private fun orderedSubtree(index: Int, map: Map<Int, List<Int>>): List<Int> {
        val result = ArrayList<Int>()
        val seen = BooleanArray(items.size)
        fun walk(current: Int) {
            if (seen[current]) {
                return
            }
            seen[current] = true
            result.add(current)
            for (child in map[current] ?: emptyList()) {
                walk(child)
            }
        }
        walk(index)
        return result
    }

    /** True for [ROOT] or an existing component. */
    private fun validParent(parentIndex: Int): Boolean =
        parentIndex == ROOT || parentIndex in items.indices

    /**
     * Where a new child of [parentIndex] belongs in the array so that it lands at
     * sibling position [insertAt].
     *
     * Siblings are not necessarily contiguous in a cache authored interface, so
     * "append" means after the end of the last sibling's whole subtree.
     */
    private fun insertionPosition(parentIndex: Int, insertAt: Int): Int {
        val siblings = children(parentIndex)
        if (insertAt in siblings.indices) {
            return siblings[insertAt]
        }
        val last = siblings.lastOrNull()
        if (last != null) {
            return collectSubtree(last, childMap()).max() + 1
        }
        return if (parentIndex in items.indices) parentIndex + 1 else items.size
    }

    /**
     * The [insertionPosition] equivalent for [reparent], counted over [remaining]
     * (the array with the moving subtree taken out) and ignoring anything in
     * [moving].
     */
    private fun insertionPositionAmong(
        remaining: List<Int>,
        moving: Set<Int>,
        parentIndex: Int,
        insertAt: Int,
        map: Map<Int, List<Int>>
    ): Int {
        val siblings = (map[parentIndex] ?: emptyList()).filter { it !in moving }
        if (insertAt in siblings.indices) {
            return remaining.indexOf(siblings[insertAt]).coerceAtLeast(0)
        }
        val last = siblings.lastOrNull()
        if (last != null) {
            val end = collectSubtree(last, map).filter { it !in moving }.max()
            return remaining.indexOf(end) + 1
        }
        if (parentIndex in items.indices) {
            return remaining.indexOf(parentIndex) + 1
        }
        return remaining.size
    }

    /**
     * The given indices with anything that also has an ancestor in the set removed,
     * sorted ascending. Deleting or duplicating both a parent and its child would
     * otherwise process the child twice.
     */
    private fun topLevel(indices: Collection<Int>): List<Int> {
        val requested = indices.filter { it in items.indices }.toHashSet()
        return requested.filter { index ->
            var current = items[index].parent
            var guard = items.size
            while (current in items.indices && guard-- > 0) {
                if (current in requested) {
                    return@filter false
                }
                current = items[current].parent
            }
            true
        }.sorted()
    }

    private fun applyTypeDefaults(definition: InterfaceComponentDefinition, type: Int) {
        val defaults = ComponentTemplates.defaults(type)
        definition.scrollWidth = defaults.scrollWidth
        definition.scrollHeight = defaults.scrollHeight
        definition.disableHover = defaults.disableHover
        definition.colour = defaults.colour
        definition.filled = defaults.filled
        definition.alpha = defaults.alpha
        definition.fontId = defaults.fontId
        definition.text = defaults.text
        definition.lineHeight = defaults.lineHeight
        definition.horizontalTextAlign = defaults.horizontalTextAlign
        definition.verticalTextAlign = defaults.verticalTextAlign
        definition.shaded = defaults.shaded
        definition.defaultImage = defaults.defaultImage
        definition.imageRotation = defaults.imageRotation
        definition.aBoolean4861 = defaults.aBoolean4861
        definition.imageRepeat = defaults.imageRepeat
        definition.rotation = defaults.rotation
        definition.backgroundColour = defaults.backgroundColour
        definition.flipVertical = defaults.flipVertical
        definition.flipHorizontal = defaults.flipHorizontal
        definition.defaultMediaType = defaults.defaultMediaType
        definition.defaultMediaId = defaults.defaultMediaId
        definition.animated = defaults.animated
        definition.centreType = defaults.centreType
        definition.ignoreZBuffer = defaults.ignoreZBuffer
        definition.viewportX = defaults.viewportX
        definition.viewportY = defaults.viewportY
        definition.viewportZ = defaults.viewportZ
        definition.graphicPitch = defaults.graphicPitch
        definition.graphicRoll = defaults.graphicRoll
        definition.graphicYaw = defaults.graphicYaw
        definition.graphicScale = defaults.graphicScale
        definition.animation = defaults.animation
        definition.viewportWidth = defaults.viewportWidth
        definition.viewportHeight = defaults.viewportHeight
        definition.lineWidth = defaults.lineWidth
        definition.lineMirrored = defaults.lineMirrored
    }

    companion object {

        /** Parent value meaning "no parent". */
        const val ROOT = ComponentDefinitions.ROOT

        /**
         * Decodes interface [id] out of cache index 3.
         *
         * Returns an empty document when the archive is absent, which is exactly
         * what [InterfaceStore.newInterface] wants; check
         * [InterfaceStore.exists] first if the difference matters.
         */
        fun decode(cache: Cache, id: Int): InterfaceDocument {
            val decoder = InterfaceDecoder()
            val definitions = decoder.create(id + 1)
            decoder.load(definitions, cache, id)
            return of(definitions[id], id)
        }

        /** Wraps an already decoded definition. */
        fun of(definition: InterfaceDefinition, id: Int = definition.id): InterfaceDocument =
            InterfaceDocument(id, definition.components?.toList() ?: emptyList())

        /** An interface with no components yet. */
        fun empty(id: Int): InterfaceDocument = InterfaceDocument(id)
    }
}
