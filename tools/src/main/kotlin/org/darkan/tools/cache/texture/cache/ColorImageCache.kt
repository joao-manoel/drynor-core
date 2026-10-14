package org.darkan.tools.cache.texture.cache

import org.darkan.tools.cache.texture.TextureGenerationException

/**
 * LRU cache for color (RGB) texture operation output.
 * Stores texture data row by row with 3 channels (red, green, blue).
 *
 * Uses an intrusive doubly-linked list for O(1) promote-to-front and
 * evict-from-tail operations, matching the RS client's LRU implementation.
 *
 * @param capacity The number of rows to cache
 * @param totalRows The total number of rows in the texture
 * @param width The width of each row in pixels
 * @param deadlineNanos Absolute [System.nanoTime] deadline for the enclosing generation,
 *        or 0 to disable the timeout check
 */
class ColorImageCache(
    private val capacity: Int,
    private val totalRows: Int,
    width: Int,
    private val deadlineNanos: Long = 0L
) {
    /** True if the current row needs to be regenerated */
    var dirty: Boolean = false

    /** RGB channel data: [cacheIndex][channel][pixelX] */
    private var paletteData: Array<Array<IntArray>> = Array(capacity) {
        Array(3) { IntArray(width) }
    }

    /** Maps row index to cache entry, null if not cached */
    private var rowToEntry: Array<ColorImageCacheEntry?> = arrayOfNulls(totalRows)

    /** Head (most recently used) of the intrusive doubly-linked list */
    private var head: ColorImageCacheEntry? = null

    /** Tail (least recently used) of the intrusive doubly-linked list */
    private var tail: ColorImageCacheEntry? = null

    /** Number of cache slots currently in use */
    private var usedSlots: Int = 0

    /** Last used row index (optimization for capacity=1) */
    private var lastRowIndex: Int = -1

    /**
     * Get the RGB palette data for the specified row.
     * Returns an array of 3 IntArrays: [red, green, blue].
     *
     * @param rowIndex The texture row index
     * @return The RGB palette data for this row
     */
    fun getPalette(rowIndex: Int): Array<IntArray> {
        if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
            throw TextureGenerationException("Texture generation timeout in cache access")
        }
        // Case 1: Full cache (capacity == totalRows)
        if (totalRows == capacity) {
            dirty = rowToEntry[rowIndex] == null
            rowToEntry[rowIndex] = SENTINEL
            return paletteData[rowIndex]
        }

        // Case 2: Single-row cache (capacity == 1)
        if (capacity == 1) {
            dirty = lastRowIndex != rowIndex
            lastRowIndex = rowIndex
            return paletteData[0]
        }

        // Case 3: LRU cache with O(1) intrusive linked list
        var entry = rowToEntry[rowIndex]
        if (entry == null) {
            dirty = true
            if (usedSlots >= capacity) {
                // Evict oldest entry (tail)
                val oldEntry = removeTail()!!
                entry = ColorImageCacheEntry(rowIndex, oldEntry.cacheIndex)
                rowToEntry[oldEntry.rowIndex] = null
            } else {
                entry = ColorImageCacheEntry(rowIndex, usedSlots++)
            }
            rowToEntry[rowIndex] = entry
        } else {
            dirty = false
            unlink(entry)
        }

        // Move to front of LRU list
        insertAtHead(entry)
        return paletteData[entry.cacheIndex]
    }

    /** Unlink an entry from the doubly-linked list. O(1). */
    private fun unlink(entry: ColorImageCacheEntry) {
        val p = entry.prev
        val n = entry.next
        if (p != null) p.next = n else head = n
        if (n != null) n.prev = p else tail = p
        entry.prev = null
        entry.next = null
    }

    /** Insert an entry at the head (most recently used). O(1). */
    private fun insertAtHead(entry: ColorImageCacheEntry) {
        entry.prev = null
        entry.next = head
        if (head != null) head!!.prev = entry
        head = entry
        if (tail == null) tail = entry
    }

    /** Remove and return the tail (least recently used) entry. O(1). */
    private fun removeTail(): ColorImageCacheEntry? {
        val t = tail ?: return null
        unlink(t)
        return t
    }

    /**
     * Clear and release the cache.
     */
    fun clear() {
        paletteData = emptyArray()
        rowToEntry = emptyArray()
        head = null
        tail = null
        usedSlots = 0
        lastRowIndex = -1
    }

    /**
     * Gets all palette data (requires full cache mode where capacity == totalRows).
     * Marks all rows as initialized.
     *
     * @return All RGB palette data rows
     * @throws RuntimeException if not in full cache mode
     */
    fun getAllPaletteData(): Array<Array<IntArray>> {
        if (capacity != totalRows) {
            throw RuntimeException("getAllPaletteData requires full cache mode")
        }
        for (i in 0 until capacity) {
            rowToEntry[i] = SENTINEL
        }
        return paletteData
    }

    companion object {
        /** Sentinel entry used for full cache mode */
        private val SENTINEL = ColorImageCacheEntry(0, 0)
    }
}

/**
 * Cache entry that tracks which row is stored at which cache index.
 * Contains intrusive doubly-linked list pointers for O(1) LRU operations.
 */
class ColorImageCacheEntry(
    val rowIndex: Int,
    val cacheIndex: Int
) {
    var prev: ColorImageCacheEntry? = null
    var next: ColorImageCacheEntry? = null
}
