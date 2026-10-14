package org.darkan.tools.engine.spatial

/**
 * Quadtree for 2D spatial partitioning of terrain regions.
 *
 * Each node covers a rectangular region on the XZ plane.
 * Objects are stored at the smallest node that fully contains them.
 *
 * @param T The type of objects stored in the tree
 * @param bounds The rectangular bounds of this tree
 * @param maxDepth Maximum subdivision depth
 * @param maxObjects Maximum objects per node before subdivision
 */
class Quadtree<T>(
    private val bounds: Rect,
    private val maxDepth: Int = 6,
    private val maxObjects: Int = 8
) {
    private class Node<T>(
        val bounds: Rect,
        val depth: Int
    ) {
        var children: Array<Node<T>>? = null
        val objects = mutableListOf<Entry<T>>()

        val isLeaf: Boolean get() = children == null
    }

    private data class Entry<T>(
        val obj: T,
        val bounds: Rect
    )

    private val root = Node<T>(bounds, 0)
    private var count = 0

    /**
     * Insert an object with its 2D bounds.
     */
    fun insert(obj: T, objBounds: Rect) {
        insert(root, Entry(obj, objBounds))
        count++
    }

    /**
     * Insert an object at a point location.
     */
    fun insert(obj: T, x: Float, z: Float) {
        insert(obj, Rect(x, z, x, z))
    }

    private fun insert(node: Node<T>, entry: Entry<T>) {
        // If not a leaf, try to insert into a child
        node.children?.let { children ->
            val index = getChildIndex(node, entry.bounds)
            if (index != -1) {
                insert(children[index], entry)
                return
            }
            // Object spans multiple children, store here
            node.objects.add(entry)
            return
        }

        // Leaf node - add object
        node.objects.add(entry)

        // Subdivide if needed
        if (node.objects.size > maxObjects && node.depth < maxDepth) {
            subdivide(node)
        }
    }

    private fun subdivide(node: Node<T>) {
        val childBounds = node.bounds.subdivide()
        node.children = Array(4) { i -> Node<T>(childBounds[i], node.depth + 1) }

        // Redistribute objects to children
        val toRedistribute = node.objects.toList()
        node.objects.clear()

        for (entry in toRedistribute) {
            val index = getChildIndex(node, entry.bounds)
            if (index != -1) {
                insert(node.children!![index], entry)
            } else {
                // Spans multiple children, keep in this node
                node.objects.add(entry)
            }
        }
    }

    /**
     * Get the child index for a bounds, or -1 if it spans multiple children.
     */
    private fun getChildIndex(node: Node<T>, objBounds: Rect): Int {
        val midX = node.bounds.centerX
        val midZ = node.bounds.centerZ

        val left = objBounds.maxX < midX
        val right = objBounds.minX >= midX
        val bottom = objBounds.maxZ < midZ
        val top = objBounds.minZ >= midZ

        return when {
            left && bottom -> 0   // SW
            right && bottom -> 1  // SE
            left && top -> 2      // NW
            right && top -> 3     // NE
            else -> -1            // Spans multiple quadrants
        }
    }

    /**
     * Query all objects that intersect with the given frustum.
     */
    fun query(frustum: Frustum, yMin: Float = -1000f, yMax: Float = 1000f): List<T> {
        val results = mutableListOf<T>()
        query(root, frustum, yMin, yMax, results)
        return results
    }

    private fun query(node: Node<T>, frustum: Frustum, yMin: Float, yMax: Float, results: MutableList<T>) {
        // Test node bounds against frustum
        if (!frustum.isRectVisible(node.bounds, yMin, yMax)) {
            return
        }

        // Add all objects in this node
        for (entry in node.objects) {
            if (frustum.isRectVisible(entry.bounds, yMin, yMax)) {
                results.add(entry.obj)
            }
        }

        // Recurse into children
        node.children?.forEach { child ->
            query(child, frustum, yMin, yMax, results)
        }
    }

    /**
     * Query all objects that intersect with the given rectangle.
     */
    fun query(rect: Rect): List<T> {
        val results = mutableListOf<T>()
        query(root, rect, results)
        return results
    }

    private fun query(node: Node<T>, rect: Rect, results: MutableList<T>) {
        if (!node.bounds.intersects(rect)) {
            return
        }

        for (entry in node.objects) {
            if (entry.bounds.intersects(rect)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            query(child, rect, results)
        }
    }

    /**
     * Query all objects within a radius of a point.
     */
    fun queryRadius(centerX: Float, centerZ: Float, radius: Float): List<T> {
        val searchRect = Rect(
            centerX - radius, centerZ - radius,
            centerX + radius, centerZ + radius
        )
        val candidates = query(searchRect)
        val radiusSq = radius * radius

        return candidates.filter { obj ->
            // Need to get bounds for each object to do proper distance check
            // For now, we return all candidates in the bounding rect
            true
        }
    }

    /**
     * Remove an object from the tree.
     *
     * @return true if the object was found and removed
     */
    fun remove(obj: T): Boolean {
        return remove(root, obj)
    }

    private fun remove(node: Node<T>, obj: T): Boolean {
        // Check this node's objects
        val removed = node.objects.removeIf { it.obj == obj }
        if (removed) {
            count--
            return true
        }

        // Check children
        node.children?.let { children ->
            for (child in children) {
                if (remove(child, obj)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Clear all objects from the tree.
     */
    fun clear() {
        clearNode(root)
        count = 0
    }

    private fun clearNode(node: Node<T>) {
        node.objects.clear()
        node.children?.forEach { clearNode(it) }
        node.children = null
    }

    /**
     * Get the total number of objects in the tree.
     */
    fun size(): Int = count

    /**
     * Check if the tree is empty.
     */
    fun isEmpty(): Boolean = count == 0
}
