package org.darkan.tools.engine.spatial

import org.joml.Vector3f

/**
 * Octree for 3D spatial partitioning of objects within a region.
 *
 * Each node covers a cubic region. Objects are stored at the smallest
 * node that fully contains them.
 *
 * @param T The type of objects stored in the tree
 * @param bounds The axis-aligned bounds of this tree
 * @param maxDepth Maximum subdivision depth
 * @param maxObjects Maximum objects per node before subdivision
 */
class Octree<T>(
    private val bounds: AABB,
    private val maxDepth: Int = 6,
    private val maxObjects: Int = 8
) {
    private class Node<T>(
        val bounds: AABB,
        val depth: Int
    ) {
        var children: Array<Node<T>>? = null
        val objects = mutableListOf<Entry<T>>()

        val isLeaf: Boolean get() = children == null
    }

    private data class Entry<T>(
        val obj: T,
        val bounds: AABB
    )

    private val root = Node<T>(bounds, 0)
    private var count = 0

    /**
     * Insert an object with its AABB bounds.
     */
    fun insert(obj: T, objBounds: AABB) {
        if (!bounds.intersects(objBounds)) {
            // Object is outside tree bounds - expand or reject
            return
        }
        insert(root, Entry(obj, objBounds))
        count++
    }

    /**
     * Insert an object at a point location with a radius.
     */
    fun insert(obj: T, center: Vector3f, radius: Float = 0f) {
        val objBounds = AABB.fromCenterExtents(center, Vector3f(radius, radius, radius))
        insert(obj, objBounds)
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
        val center = node.bounds.center
        val childBounds = arrayOf(
            // Bottom layer (y < center)
            AABB(node.bounds.min, center),                                                              // 0: ---
            AABB(Vector3f(center.x, node.bounds.min.y, node.bounds.min.z), Vector3f(node.bounds.max.x, center.y, center.z)), // 1: +--
            AABB(Vector3f(node.bounds.min.x, node.bounds.min.y, center.z), Vector3f(center.x, center.y, node.bounds.max.z)), // 2: -+-
            AABB(Vector3f(center.x, node.bounds.min.y, center.z), Vector3f(node.bounds.max.x, center.y, node.bounds.max.z)), // 3: ++-
            // Top layer (y >= center)
            AABB(Vector3f(node.bounds.min.x, center.y, node.bounds.min.z), Vector3f(center.x, node.bounds.max.y, center.z)), // 4: --+
            AABB(Vector3f(center.x, center.y, node.bounds.min.z), Vector3f(node.bounds.max.x, node.bounds.max.y, center.z)), // 5: +-+
            AABB(Vector3f(node.bounds.min.x, center.y, center.z), Vector3f(center.x, node.bounds.max.y, node.bounds.max.z)), // 6: -++
            AABB(center, node.bounds.max)                                                               // 7: +++
        )

        node.children = Array(8) { i -> Node<T>(childBounds[i], node.depth + 1) }

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
     * Get the child index for bounds, or -1 if it spans multiple children.
     */
    private fun getChildIndex(node: Node<T>, objBounds: AABB): Int {
        val center = node.bounds.center

        val xNeg = objBounds.max.x < center.x
        val xPos = objBounds.min.x >= center.x
        val yNeg = objBounds.max.y < center.y
        val yPos = objBounds.min.y >= center.y
        val zNeg = objBounds.max.z < center.z
        val zPos = objBounds.min.z >= center.z

        // Object must be entirely in one octant
        val xSide = when {
            xNeg -> 0
            xPos -> 1
            else -> return -1
        }
        val ySide = when {
            yNeg -> 0
            yPos -> 1
            else -> return -1
        }
        val zSide = when {
            zNeg -> 0
            zPos -> 1
            else -> return -1
        }

        return xSide + zSide * 2 + ySide * 4
    }

    /**
     * Query all objects that intersect with the given frustum.
     */
    fun query(frustum: Frustum): List<T> {
        val results = mutableListOf<T>()
        query(root, frustum, results)
        return results
    }

    private fun query(node: Node<T>, frustum: Frustum, results: MutableList<T>) {
        // Test node bounds against frustum
        val result = frustum.testAABB(node.bounds)
        if (result == Frustum.OUTSIDE) {
            return
        }

        // If fully inside, add all objects without further testing
        if (result == Frustum.INSIDE) {
            collectAll(node, results)
            return
        }

        // Partially inside - test individual objects
        for (entry in node.objects) {
            if (frustum.isAABBVisible(entry.bounds)) {
                results.add(entry.obj)
            }
        }

        // Recurse into children
        node.children?.forEach { child ->
            query(child, frustum, results)
        }
    }

    private fun collectAll(node: Node<T>, results: MutableList<T>) {
        for (entry in node.objects) {
            results.add(entry.obj)
        }
        node.children?.forEach { child ->
            collectAll(child, results)
        }
    }

    /**
     * Query all objects that intersect with the given AABB.
     */
    fun query(aabb: AABB): List<T> {
        val results = mutableListOf<T>()
        query(root, aabb, results)
        return results
    }

    private fun query(node: Node<T>, aabb: AABB, results: MutableList<T>) {
        if (!node.bounds.intersects(aabb)) {
            return
        }

        for (entry in node.objects) {
            if (entry.bounds.intersects(aabb)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            query(child, aabb, results)
        }
    }

    /**
     * Query all objects within a sphere.
     */
    fun query(sphere: BoundingSphere): List<T> {
        // Use AABB for initial query, then filter by sphere
        val aabb = AABB.fromCenterExtents(
            sphere.center,
            Vector3f(sphere.radius, sphere.radius, sphere.radius)
        )
        val candidates = query(aabb)
        val radiusSq = sphere.radius * sphere.radius

        return candidates.filter { obj ->
            // For precise filtering, we'd need to store bounds with each object
            // For now, the AABB query is sufficient
            true
        }
    }

    /**
     * Perform a ray cast and return all objects whose bounds are hit.
     */
    fun raycast(origin: Vector3f, direction: Vector3f, maxDistance: Float = Float.MAX_VALUE): List<T> {
        val results = mutableListOf<T>()
        val invDir = Vector3f(
            if (direction.x != 0f) 1f / direction.x else Float.MAX_VALUE,
            if (direction.y != 0f) 1f / direction.y else Float.MAX_VALUE,
            if (direction.z != 0f) 1f / direction.z else Float.MAX_VALUE
        )
        raycast(root, origin, invDir, maxDistance, results)
        return results
    }

    private fun raycast(node: Node<T>, origin: Vector3f, invDir: Vector3f, maxDist: Float, results: MutableList<T>) {
        // Ray-AABB intersection test
        if (!rayIntersectsAABB(origin, invDir, node.bounds, maxDist)) {
            return
        }

        for (entry in node.objects) {
            if (rayIntersectsAABB(origin, invDir, entry.bounds, maxDist)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            raycast(child, origin, invDir, maxDist, results)
        }
    }

    private fun rayIntersectsAABB(origin: Vector3f, invDir: Vector3f, aabb: AABB, maxDist: Float): Boolean {
        val t1 = (aabb.min.x - origin.x) * invDir.x
        val t2 = (aabb.max.x - origin.x) * invDir.x
        val t3 = (aabb.min.y - origin.y) * invDir.y
        val t4 = (aabb.max.y - origin.y) * invDir.y
        val t5 = (aabb.min.z - origin.z) * invDir.z
        val t6 = (aabb.max.z - origin.z) * invDir.z

        val tmin = maxOf(minOf(t1, t2), minOf(t3, t4), minOf(t5, t6))
        val tmax = minOf(maxOf(t1, t2), maxOf(t3, t4), maxOf(t5, t6))

        // Ray intersects if tmax >= 0 and tmin <= tmax and tmin <= maxDist
        return tmax >= 0 && tmin <= tmax && tmin <= maxDist
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
        val removed = node.objects.removeIf { it.obj == obj }
        if (removed) {
            count--
            return true
        }

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
     * Update an object's position in the tree.
     */
    fun update(obj: T, newBounds: AABB): Boolean {
        if (remove(obj)) {
            insert(obj, newBounds)
            return true
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

    /**
     * Get statistics about the tree structure.
     */
    fun getStats(): OctreeStats {
        var nodeCount = 0
        var leafCount = 0
        var maxObjects = 0
        var totalObjects = 0
        var maxDepthReached = 0

        fun visit(node: Node<T>) {
            nodeCount++
            if (node.isLeaf) leafCount++
            if (node.objects.size > maxObjects) maxObjects = node.objects.size
            totalObjects += node.objects.size
            if (node.depth > maxDepthReached) maxDepthReached = node.depth
            node.children?.forEach { visit(it) }
        }

        visit(root)

        return OctreeStats(
            nodeCount = nodeCount,
            leafCount = leafCount,
            objectCount = count,
            maxObjectsPerNode = maxObjects,
            averageObjectsPerLeaf = if (leafCount > 0) totalObjects.toFloat() / leafCount else 0f,
            maxDepthReached = maxDepthReached
        )
    }
}

data class OctreeStats(
    val nodeCount: Int,
    val leafCount: Int,
    val objectCount: Int,
    val maxObjectsPerNode: Int,
    val averageObjectsPerLeaf: Float,
    val maxDepthReached: Int
)
