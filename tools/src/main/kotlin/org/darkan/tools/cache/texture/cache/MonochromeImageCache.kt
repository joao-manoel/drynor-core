package org.darkan.tools.cache.texture.cache

import org.darkan.tools.cache.texture.TextureGenerationException

/**
 * LRU cache for monochrome (single-channel) texture operation output.
 * Stores texture data row by row with 1 channel (grayscale).
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
class MonochromeImageCache(
    private val capacity: Int,
    private val totalRows: Int,
    width: Int,
    private val deadlineNanos: Long = 0L
) {
    /** True if the current row needs to be regenerated */
    var dirty: Boolean = false

    /** Grayscale channel data: [cacheIndex][pixelX] */
    private var paletteData: Array<IntArray?> = Array(capacity) { IntArray(width) }

    /** Maps row index to cache entry, null if not cached */
    private var rowToEntry: Array<MonochromeImageCacheEntry?> = arrayOfNulls(totalRows)

    /** Head (most recently used) of the intrusive doubly-linked list */
    private var head: MonochromeImageCacheEntry? = null

    /** Tail (least recently used) of the intrusive doubly-linked list */
    private var tail: MonochromeImageCacheEntry? = null

    /** Number of cache slots currently in use */
    private var usedSlots: Int = 0

    /** Last used row index (optimization for capacity=1) */
    private var lastRowIndex: Int = -1

    /**
     * Get the grayscale palette data for the specified row.
     *
     * @param rowIndex The texture row index
     * @return The grayscale palette data for this row
     */
    fun getPalette(rowIndex: Int): IntArray {
        if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
            throw TextureGenerationException("Texture generation timeout in cache access")
        }
        // Case 1: Full cache (capacity == totalRows)
        if (totalRows == capacity) {
            dirty = rowToEntry[rowIndex] == null
            rowToEntry[rowIndex] = SENTINEL
            return paletteData[rowIndex]!!
        }

        // Case 2: Single-row cache (capacity == 1)
        if (capacity == 1) {
            dirty = lastRowIndex != rowIndex
            lastRowIndex = rowIndex
            return paletteData[0]!!
        }

        // Case 3: LRU cache with O(1) intrusive linked list
        var entry = rowToEntry[rowIndex]
        if (entry == null) {
            dirty = true
            if (usedSlots >= capacity) {
                // Evict oldest entry (tail)
                val oldEntry = removeTail()!!
                entry = MonochromeImageCacheEntry(rowIndex, oldEntry.cacheIndex)
                rowToEntry[oldEntry.rowIndex] = null
            } else {
                entry = MonochromeImageCacheEntry(rowIndex, usedSlots++)
            }
            rowToEntry[rowIndex] = entry
        } else {
            dirty = false
            unlink(entry)
        }

        // Move to front of LRU list
        insertAtHead(entry)
        return paletteData[entry.cacheIndex]!!
    }

    /** Unlink an entry from the doubly-linked list. O(1). */
    private fun unlink(entry: MonochromeImageCacheEntry) {
        val p = entry.prev
        val n = entry.next
        if (p != null) p.next = n else head = n
        if (n != null) n.prev = p else tail = p
        entry.prev = null
        entry.next = null
    }

    /** Insert an entry at the head (most recently used). O(1). */
    private fun insertAtHead(entry: MonochromeImageCacheEntry) {
        entry.prev = null
        entry.next = head
        if (head != null) head!!.prev = entry
        head = entry
        if (tail == null) tail = entry
    }

    /** Remove and return the tail (least recently used) entry. O(1). */
    private fun removeTail(): MonochromeImageCacheEntry? {
        val t = tail ?: return null
        unlink(t)
        return t
    }

    /**
     * Reset and release the cache.
     */
    fun reset() {
        for (i in paletteData.indices) {
            paletteData[i] = null
        }
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
     * @return All palette data rows
     * @throws RuntimeException if not in full cache mode
     */
    fun getAllPaletteData(): Array<IntArray?> {
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
        private val SENTINEL = MonochromeImageCacheEntry(0, 0)
    }
}

/**
 * Cache entry that tracks which row is stored at which cache index.
 * Contains intrusive doubly-linked list pointers for O(1) LRU operations.
 */
class MonochromeImageCacheEntry(
    val rowIndex: Int,
    val cacheIndex: Int
) {
    var prev: MonochromeImageCacheEntry? = null
    var next: MonochromeImageCacheEntry? = null
}
