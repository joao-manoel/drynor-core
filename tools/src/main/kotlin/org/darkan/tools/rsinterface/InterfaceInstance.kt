package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceDefinition

/**
 * A live instance of a cache interface: the component tree, the mutable
 * [ComponentState] for every component, and the layout pass that turns the
 * cache's base position/size plus the position/size modes into a concrete box
 * model.
 *
 * The layout rules are a direct transcription of the client's
 * `InterfaceRenderer.initSizes` and `InterfaceComponentRenderer.componentPositionAdjustment`.
 *
 * A CS2 interpreter drives this class through [state] / [stateByHash] to reach a
 * component, and through [openSub] / [closeSub] for `if_openSub` / `if_closeSub`.
 * Nothing here needs to be told to relayout - [ComponentState] mutators mark the
 * instance dirty and [layout] re-runs on the next frame.
 */
class InterfaceInstance(
    val id: Int,
    val definition: InterfaceDefinition
) {
    /** Component states in cache order, which is also the draw order. */
    val components: List<ComponentState>

    /** Ordered child indices per parent index; the root's children use -1. */
    private val childIndices: Map<Int, List<Int>>

    /** Sub-interfaces mounted onto a container, keyed by container index. */
    private val subInterfaces = LinkedHashMap<Int, InterfaceInstance>()

    /**
     * Runtime-created children per parent index, in slot order with gaps kept as
     * nulls - the client's `Component.slotChildren`. See [createChild].
     */
    private val slotChildren = LinkedHashMap<Int, ArrayList<ComponentState?>>()

    /** Every runtime component by its synthetic index, so [state] can find one. */
    private val runtimeComponents = LinkedHashMap<Int, ComponentState>()

    /** Synthetic indices freed by a delete, reused before allocating new ones. */
    private val freeIndices = ArrayDeque<Int>()

    private var nextRuntimeIndex = 0

    private var layoutDirty = true
    private var laidOutWidth = -1
    private var laidOutHeight = -1

    init {
        val definitions = definition.components ?: emptyArray()
        components = definitions.mapIndexed { index, component ->
            ComponentState(component, index, id).also { state ->
                state.invalidateLayout = { layoutDirty = true }
            }
        }
        childIndices = components.groupBy({ it.parent }, { it.index })
        nextRuntimeIndex = components.size
    }

    /**
     * The component at [index], whether it came from the cache or from
     * `CC_CREATE`. Runtime components are numbered above the cache components, so
     * one lookup covers both and their packed hashes stay unique.
     */
    fun state(index: Int): ComponentState? = components.getOrNull(index) ?: runtimeComponents[index]

    /**
     * Resolves the packed `interfaceId shl 16 or componentIndex` hash that CS2
     * and the network protocol use, searching mounted sub-interfaces too.
     */
    fun stateByHash(hash: Int): ComponentState? {
        if ((hash shr 16) == id) {
            return state(hash and 0xffff)
        }
        for (sub in subInterfaces.values) {
            val found = sub.stateByHash(hash)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Children of [parentIndex] in draw order; pass -1 for the root children.
     *
     * Cache children come first and runtime children after, which is the order
     * the client draws them in: `drawComponents` walks the interface's own
     * components and only then the container's slot children.
     */
    fun children(parentIndex: Int): List<ComponentState> {
        val fixed = childIndices[parentIndex]?.mapNotNull { components.getOrNull(it) } ?: emptyList()
        val slots = slotChildren[parentIndex] ?: return fixed
        return fixed + slots.filterNotNull()
    }

    // --- Runtime components (`CC_CREATE` and friends) -----------------------

    /**
     * Creates a runtime child of type [type] in slot [slot] of [parentIndex], as
     * `CC_CREATE` does, replacing whatever occupied that slot.
     *
     * The child is backed by a blank [world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition]
     * carrying only its type and parent - there is nothing on disk to seed it from
     * - so every field starts at the definition's default, which is what the
     * client's freshly constructed `Component` does too. It is given a synthetic
     * index above the cache components so its packed hash cannot collide with a
     * real one, and that index is recycled once the child is deleted.
     *
     * Returns null when the parent does not exist, or for the type 0 the client
     * rejects outright.
     */
    fun createChild(parentIndex: Int, type: Int, slot: Int): ComponentState? {
        if (type == ComponentType.CONTAINER || slot < 0 || slot > ComponentDefinitions.MAX_COMPONENTS) {
            return null
        }
        if (state(parentIndex) == null) {
            return null
        }
        val slots = slotChildren.getOrPut(parentIndex) { ArrayList() }
        while (slots.size <= slot) {
            slots.add(null)
        }
        slots[slot]?.let(::dispose)

        val index = freeIndices.removeFirstOrNull() ?: nextRuntimeIndex++
        val definition = ComponentDefinitions.create(id, index, type)
        definition.parent = parentIndex
        val child = ComponentState(definition, index, id, slot)
        child.invalidateLayout = { layoutDirty = true }
        slots[slot] = child
        runtimeComponents[index] = child
        layoutDirty = true
        return child
    }

    /** Drops every runtime child of [parentIndex] (`CC_DELETEALL`). */
    fun deleteChildren(parentIndex: Int) {
        val slots = slotChildren.remove(parentIndex) ?: return
        slots.forEach { child -> child?.let(::dispose) }
        layoutDirty = true
    }

    /** Drops one runtime component (`CC_DELETE`); false when it was not one. */
    fun deleteChild(state: ComponentState): Boolean {
        val slots = slotChildren[state.parent] ?: return false
        if (state.slotId !in slots.indices || slots[state.slotId] !== state) {
            return false
        }
        slots[state.slotId] = null
        dispose(state)
        layoutDirty = true
        return true
    }

    /**
     * How many runtime children [parentIndex] has before the first empty slot,
     * which is both `IF_GETSUBCOUNT`'s answer and the slot the next `CC_CREATE`
     * should use. The opcode table calls it `IF_GETNEXTSUBID`; the client counts.
     */
    fun slotCount(parentIndex: Int): Int {
        val slots = slotChildren[parentIndex] ?: return 0
        val gap = slots.indexOfFirst { it == null }
        return if (gap == -1) slots.size else gap
    }

    /** The runtime child of [parentIndex] in [slot], or null. */
    fun slotChild(parentIndex: Int, slot: Int): ComponentState? =
        slotChildren[parentIndex]?.getOrNull(slot)

    /**
     * The component the client's `Component.listIcomponent` addresses: the component
     * [hash] names when [slot] is negative, otherwise that component's runtime child
     * in [slot].
     *
     * Several opcodes address a component this way rather than by hash alone, because
     * a runtime child has no hash a script could have been told - only the slot it was
     * created in.
     */
    fun slotChildByHash(hash: Int, slot: Int): ComponentState? {
        val owner = stateByHash(hash) ?: return null
        if (slot < 0) {
            return owner
        }
        return slotChild(owner.index, slot)
    }

    /** Retires a runtime component and everything it in turn created. */
    private fun dispose(state: ComponentState) {
        slotChildren.remove(state.index)?.forEach { child -> child?.let(::dispose) }
        subInterfaces.remove(state.index)
        runtimeComponents.remove(state.index)
        freeIndices.addLast(state.index)
    }

    fun subInterface(containerIndex: Int): InterfaceInstance? = subInterfaces[containerIndex]

    /** Mounts [instance] inside the container at [containerIndex] (`if_openSub`). */
    fun openSub(containerIndex: Int, instance: InterfaceInstance) {
        subInterfaces[containerIndex] = instance
        layoutDirty = true
    }

    /** Unmounts whatever is inside the container at [containerIndex]. */
    fun closeSub(containerIndex: Int) {
        if (subInterfaces.remove(containerIndex) != null) {
            layoutDirty = true
        }
    }

    fun invalidateLayout() {
        layoutDirty = true
    }

    /**
     * Brings the box model up to date against whatever viewport is already known,
     * falling back to [DEFAULT_VIEWPORT_WIDTH] x [DEFAULT_VIEWPORT_HEIGHT].
     *
     * Something has to lay the interface out *before* its scripts run. The client
     * never runs a load hook on an interface it has not already drawn once, so
     * every geometry opcode a load script calls reads a box model that is already
     * resolved; run the scripts first and they all read zero instead - and the
     * scripts notice, because several of them decide whether a widget fits by
     * comparing its height against its container's.
     *
     * Callers that know the real viewport should use [layout] instead; this exists
     * for the headless paths that have no viewport at all.
     */
    fun layoutDeclared() {
        layout(
            if (laidOutWidth > 0) laidOutWidth else DEFAULT_VIEWPORT_WIDTH,
            if (laidOutHeight > 0) laidOutHeight else DEFAULT_VIEWPORT_HEIGHT,
        )
    }

    /**
     * Re-resolves one component's own size and position against its parent's
     * current content box - the client's `CS2SharedOps.method913`.
     *
     * The client runs this on every `IF_SETSIZE` and `IF_SETPOSITION`, which is
     * what lets a script set a size and read it straight back. Merely marking the
     * interface dirty and waiting for the next frame is not enough: `CC_GETHEIGHT`
     * immediately after `CC_SETSIZE` is how every list-building script computes its
     * row stride, and it reads 0 if nothing has resolved the size yet - which makes
     * every row land on top of the first one.
     *
     * Only this component is touched, as in the client; its children are picked up
     * by the next full [layout]. The content box is taken the way [layoutChildren]
     * takes it, scroll extent included, so a component cannot be resolved here one
     * way and re-resolved differently a frame later.
     */
    fun resolve(state: ComponentState) {
        val parent = parentOf(state)
        val width: Int
        val height: Int
        if (parent == null) {
            width = if (laidOutWidth > 0) laidOutWidth else DEFAULT_VIEWPORT_WIDTH
            height = if (laidOutHeight > 0) laidOutHeight else DEFAULT_VIEWPORT_HEIGHT
        } else {
            width = if (parent.scrollWidth != 0) parent.scrollWidth else parent.width
            height = if (parent.scrollHeight != 0) parent.scrollHeight else parent.height
        }
        applySize(state, width, height)
        applyPosition(state, width, height)
        clampScroll(state)
    }

    /** Resets every component back to the values decoded from the cache. */
    fun reset() {
        components.forEach { it.reset() }
        subInterfaces.clear()
        slotChildren.clear()
        runtimeComponents.clear()
        freeIndices.clear()
        nextRuntimeIndex = components.size
        layoutDirty = true
    }

    /**
     * Recomputes the box model against a root viewport of [width] x [height].
     * Cheap to call every frame: it only does work when something changed.
     */
    fun layout(width: Int, height: Int) {
        if (!layoutDirty && width == laidOutWidth && height == laidOutHeight) {
            return
        }
        laidOutWidth = width
        laidOutHeight = height
        layoutDirty = false
        layoutChildren(-1, width, height)
    }

    /**
     * Collects every visible component under ([pointX], [pointY]) in draw order,
     * back to front, walking the same clip rules the renderer uses so that a
     * component scrolled or clipped out of its parent is not pickable.
     *
     * The interface is assumed to be drawn at ([originX], [originY]) inside a
     * [width] x [height] viewport, the same arguments passed to the renderer.
     */
    fun hitTest(
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        pointX: Int,
        pointY: Int
    ): List<ComponentState> {
        layout(width, height)
        val hits = ArrayList<ComponentState>()
        hitTestChildren(
            -1, originX, originY, originX + width, originY + height,
            originX, originY, pointX, pointY, hits
        )
        return hits
    }

    /**
     * The components under ([pointX], [pointY]) that the client would still be
     * interacting with, in draw order, back to front.
     *
     * This is [hitTest] with the click-through rule applied: a component with
     * [ComponentState.noClickThrough] set swallows the pointer, so everything
     * drawn behind it is dropped. The client spells this the other way round - it
     * queues hover hooks as it draws and calls `resetPendingHooks` when it reaches
     * a blocker - but the outcome is the same, and the list form is what the
     * interaction runtime needs to diff hover state frame to frame.
     *
     * "Behind" means earlier in [hitTest]'s draw order, which is why that order has
     * to put a container ahead of its own children: a blocker keeps everything drawn
     * on top of it, and its children are drawn on top of it. A popup layer that
     * blocks click-through must not swallow its own rows and scrollbar.
     *
     * Every component in the chain is hovered, not just the deepest one: the
     * client fires the enter/leave hooks for each component the pointer is inside.
     */
    fun hoverChain(
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        pointX: Int,
        pointY: Int
    ): List<ComponentState> {
        val hits = hitTest(originX, originY, width, height, pointX, pointY)
        val blocker = hits.indexOfLast { it.noClickThrough }
        return if (blocker <= 0) hits else hits.subList(blocker, hits.size)
    }

    /**
     * The topmost component under the pointer that the interaction runtime should
     * treat as the click target: the last hit that is [ComponentState.interactive]
     * and, where possible, not a bare container - clicking a button should select
     * the button, not the layer holding it.
     */
    fun interactiveAt(
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        pointX: Int,
        pointY: Int
    ): ComponentState? {
        val chain = hoverChain(originX, originY, width, height, pointX, pointY)
        return chain.lastOrNull { it.interactive && it.type != ComponentType.CONTAINER }
            ?: chain.lastOrNull { it.interactive }
    }

    /** The component [state] is parented to, or null when it sits at the root. */
    fun parentOf(state: ComponentState): ComponentState? = state(state.parent)

    /** True when [state] belongs to this instance or to one mounted inside it. */
    fun contains(state: ComponentState): Boolean =
        (state.interfaceId == id && state(state.index) === state) ||
            subInterfaces.values.any { it.contains(state) }

    /**
     * The component one level *out* from [state], crossing interface boundaries:
     * its parent within its own interface, or - when it sits at the root of a
     * mounted sub-interface - the container that sub-interface hangs from.
     *
     * This is what `IF_GETPARENTLAYER` answers, and it is not the same question as
     * `IF_GETLAYER` ([parentOf]), which stops at the root of the component's own
     * interface. Scripts walk outwards with this one to decide whether a component
     * is currently visible inside the game frame, so it must eventually reach null:
     * an editor showing a single interface has nothing above it, and a walk that
     * never terminates is an infinite loop rather than a wrong answer.
     */
    fun enclosing(state: ComponentState): ComponentState? {
        if (state.interfaceId == id) {
            return parentOf(state)
        }
        for ((containerIndex, sub) in subInterfaces) {
            if (sub.contains(state)) {
                return sub.enclosing(state) ?: components.getOrNull(containerIndex)
            }
        }
        return null
    }

    /** [state]'s parents, nearest first, stopping at the root of this interface. */
    fun ancestors(state: ComponentState): List<ComponentState> {
        val chain = ArrayList<ComponentState>()
        var current = parentOf(state)
        // A malformed parent cycle would otherwise hang the frame, so bound the walk
        // by the component count - no legitimate chain can be longer.
        while (current != null && chain.size < components.size + runtimeComponents.size) {
            chain.add(current)
            current = parentOf(current)
        }
        return chain
    }

    /**
     * The nearest container at or above [state] that has somewhere to scroll to,
     * which is where a wheel event over [state] lands.
     *
     * Only counts a container as scrollable when its scroll extent actually
     * overflows its box, matching the clamp in [clampScroll] - a container whose
     * scroll size equals its size has no overflow and the wheel should keep
     * travelling up to whatever encloses it.
     */
    fun scrollableAncestor(state: ComponentState): ComponentState? {
        if (scrollable(state)) {
            return state
        }
        return ancestors(state).firstOrNull { scrollable(it) }
    }

    private fun scrollable(state: ComponentState): Boolean =
        state.type == ComponentType.CONTAINER &&
            (state.scrollHeight > state.height || state.scrollWidth > state.width)

    /**
     * Moves [state]'s scroll offsets by ([deltaX], [deltaY]) and clamps them,
     * returning true when anything actually moved. Clamping here rather than
     * waiting for the layout pass keeps a wheel event from reporting movement the
     * next relayout would undo.
     */
    fun scrollBy(state: ComponentState, deltaX: Int, deltaY: Int): Boolean {
        val beforeX = state.scrollX
        val beforeY = state.scrollY
        state.setScrollPos(state.scrollX + deltaX, state.scrollY + deltaY)
        clampScroll(state)
        return state.scrollX != beforeX || state.scrollY != beforeY
    }

    @Suppress("LongParameterList")
    private fun hitTestChildren(
        parentIndex: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        offsetX: Int,
        offsetY: Int,
        pointX: Int,
        pointY: Int,
        hits: MutableList<ComponentState>
    ) {
        for (child in children(parentIndex)) {
            if (child.hidden) {
                continue
            }
            val startX = child.x + offsetX
            val startY = child.y + offsetY
            val box = clipBox(child, startX, startY, clipLeft, clipTop, clipRight, clipBottom)
            if (box[0] >= box[2] || box[1] >= box[3]) {
                continue
            }
            // The component goes in *before* its children, because that is the order
            // the renderer draws them in: a container is painted and its children
            // land on top. Collecting children first would put a container behind
            // its own contents in the list, and [hoverChain] reads the list as draw
            // order - a container with `noClickThrough` would then swallow the very
            // children it contains rather than what is drawn behind it.
            if (pointX >= box[0] && pointX < box[2] && pointY >= box[1] && pointY < box[3]) {
                hits.add(child)
            }
            if (child.type == ComponentType.CONTAINER) {
                hitTestChildren(
                    child.index, box[0], box[1], box[2], box[3],
                    startX - child.scrollX, startY - child.scrollY, pointX, pointY, hits
                )
                subInterfaces[child.index]?.hitTestChildren(
                    -1, box[0], box[1], box[2], box[3], startX, startY, pointX, pointY, hits
                )
            }
        }
    }

    /**
     * The visible rectangle of a component as left/top/right/bottom, matching the
     * client's clip arithmetic: type 2 never narrows the region, and lines are one
     * pixel wider and taller because their end point is inclusive.
     */
    internal fun clipBox(
        state: ComponentState,
        startX: Int,
        startY: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int
    ): IntArray {
        if (state.type == ComponentType.TYPE_2) {
            return intArrayOf(clipLeft, clipTop, clipRight, clipBottom)
        }
        var right = startX + state.width
        var bottom = startY + state.height
        if (state.type == ComponentType.LINE) {
            right++
            bottom++
        }
        return intArrayOf(
            maxOf(startX, clipLeft),
            maxOf(startY, clipTop),
            minOf(right, clipRight),
            minOf(bottom, clipBottom)
        )
    }

    private fun layoutChildren(parentIndex: Int, parentWidth: Int, parentHeight: Int) {
        for (child in children(parentIndex)) {
            applySize(child, parentWidth, parentHeight)
            applyPosition(child, parentWidth, parentHeight)
            clampScroll(child)
            // Only containers propagate a coordinate space to children, exactly as
            // the client's updateInterfaceDimensions does. Components parented to a
            // non-container are never laid out, and never drawn, in either renderer.
            if (child.type == ComponentType.CONTAINER) {
                val innerWidth = if (child.scrollWidth != 0) child.scrollWidth else child.width
                val innerHeight = if (child.scrollHeight != 0) child.scrollHeight else child.height
                layoutChildren(child.index, innerWidth, innerHeight)
                val sub = subInterfaces[child.index]
                if (sub != null) {
                    sub.invalidateLayout()
                    sub.layout(innerWidth, innerHeight)
                }
            }
        }
    }

    /**
     * Size modes, from `InterfaceRenderer.initSizes`:
     * - 0 absolute: the base size verbatim
     * - 1 inset: the parent extent minus the base size
     * - 2 proportional: `base * parent / 16384`
     * - 3 unchanged: keeps whatever size the component already had
     * - 4 aspect: derived from the other axis via [ComponentState.aspectX]/[ComponentState.aspectY]
     *
     * Mode 4 is resolved after both axes, so a width of mode 4 uses the height
     * computed by this same pass - matching the client's ordering.
     */
    private fun applySize(state: ComponentState, parentWidth: Int, parentHeight: Int) {
        when (state.horizontalSizeMode) {
            SIZE_ABSOLUTE -> state.width = state.baseWidth
            SIZE_INSET -> state.width = parentWidth - state.baseWidth
            SIZE_PROPORTIONAL -> state.width = (state.baseWidth * parentWidth) shr 14
        }
        when (state.verticalSizeMode) {
            SIZE_ABSOLUTE -> state.height = state.baseHeight
            SIZE_INSET -> state.height = parentHeight - state.baseHeight
            SIZE_PROPORTIONAL -> state.height = (parentHeight * state.baseHeight) shr 14
        }
        if (state.horizontalSizeMode == SIZE_ASPECT && state.aspectY != 0) {
            state.width = state.aspectX * state.height / state.aspectY
        }
        if (state.verticalSizeMode == SIZE_ASPECT && state.aspectX != 0) {
            state.height = state.width * state.aspectY / state.aspectX
        }
    }

    /**
     * Position modes, from `InterfaceComponentRenderer.componentPositionAdjustment`.
     * All of them are relative to the parent's content box, and the centred and
     * right/bottom aligned modes depend on the size computed by [applySize]:
     * - 0 absolute from the top/left
     * - 1 centred, offset by the base position
     * - 2 absolute from the bottom/right
     * - 3 proportional: `base * parent / 16384`
     * - 4 centred plus a proportional offset
     * - 5 (and anything else) from the bottom/right by a proportional offset
     */
    private fun applyPosition(state: ComponentState, parentWidth: Int, parentHeight: Int) {
        state.x = when (state.horizontalPositionMode) {
            POSITION_ABSOLUTE -> state.basePositionX
            POSITION_CENTRE -> state.basePositionX + (parentWidth - state.width) / 2
            POSITION_END -> parentWidth - state.width - state.basePositionX
            POSITION_PROPORTIONAL -> (state.basePositionX * parentWidth) shr 14
            POSITION_PROPORTIONAL_CENTRE ->
                (parentWidth - state.width) / 2 + ((state.basePositionX * parentWidth) shr 14)
            else -> parentWidth - state.width - ((state.basePositionX * parentWidth) shr 14)
        }
        state.y = when (state.verticalPositionMode) {
            POSITION_ABSOLUTE -> state.basePositionY
            POSITION_CENTRE -> state.basePositionY + (parentHeight - state.height) / 2
            POSITION_END -> parentHeight - state.height - state.basePositionY
            POSITION_PROPORTIONAL -> (parentHeight * state.basePositionY) shr 14
            POSITION_PROPORTIONAL_CENTRE ->
                (parentHeight - state.height) / 2 + ((parentHeight * state.basePositionY) shr 14)
            else -> parentHeight - state.height - ((parentHeight * state.basePositionY) shr 14)
        }
    }

    /** Keeps the scroll offsets inside the scrollable overflow, as the client does. */
    internal fun clampScroll(state: ComponentState) {
        state.scrollX = state.scrollX.coerceAtMost(state.scrollWidth - state.width).coerceAtLeast(0)
        state.scrollY = state.scrollY.coerceAtMost(state.scrollHeight - state.height).coerceAtLeast(0)
    }

    companion object {
        private const val SIZE_ABSOLUTE = 0
        private const val SIZE_INSET = 1
        private const val SIZE_PROPORTIONAL = 2
        private const val SIZE_ASPECT = 4

        private const val POSITION_ABSOLUTE = 0
        private const val POSITION_CENTRE = 1
        private const val POSITION_END = 2
        private const val POSITION_PROPORTIONAL = 3
        private const val POSITION_PROPORTIONAL_CENTRE = 4

        /**
         * Viewport [layoutDeclared] assumes when nothing has told the instance how
         * big it is drawn. Interfaces are authored against the client's fixed-mode
         * canvas, which is the same size the editor's stage defaults to; the root
         * component's own base size is deliberately *not* used, because a root that
         * sizes itself as an inset of its container describes the inset rather than
         * the container.
         */
        const val DEFAULT_VIEWPORT_WIDTH = 512
        const val DEFAULT_VIEWPORT_HEIGHT = 334
    }
}
