package org.darkan.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import java.io.File

/**
 * Manages XTEA keys for map region decryption.
 * Keys are loaded from a JSON file mapping region IDs to 4-element int arrays.
 *
 * JSON format:
 * ```json
 * {
 *   "4112": [231510698, -1615880194, 42924148, 1710359040],
 *   ...
 * }
 * ```
 */
object MapXteas {
    private var keys: Map<Int, IntArray> = emptyMap()
    private var loaded = false

    /**
     * Load XTEA keys from a JSON file.
     *
     * @param path Path to the xteaKeys.json file
     * @return true if loaded successfully
     */
    fun load(path: String = "./data/map/xteaKeys.json"): Boolean {
        val file = File(path)
        if (!file.exists()) {
            logError("XTEA keys file not found: $path")
            return false
        }

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonObject = json.parseToJsonElement(file.readText()) as JsonObject

            keys = jsonObject.entries.associate { (key, value) ->
                val regionId = key.toInt()
                val keysArray = value.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
                regionId to keysArray
            }

            loaded = true
            logInfo("Loaded ${keys.size} XTEA keys")
            true
        } catch (e: Exception) {
            logError("Failed to load XTEA keys: ${e.message}", e)
            false
        }
    }

    /**
     * Get XTEA keys for a region ID.
     *
     * @param regionId The region ID (regionX shl 8 or regionY)
     * @return The 4-element XTEA key array, or null if no keys exist or all keys are zero
     */
    fun getKeys(regionId: Int): IntArray? {
        val keyArray = keys[regionId] ?: return null

        // Return null if all keys are zero (unencrypted region)
        if (keyArray.all { it == 0 }) return null

        return keyArray
    }

    /**
     * Get XTEA keys for a region by coordinates.
     *
     * @param regionX The region X coordinate
     * @param regionY The region Y coordinate
     * @return The 4-element XTEA key array, or null if no keys exist
     */
    fun getKeys(regionX: Int, regionY: Int): IntArray? {
        return getKeys((regionX shl 8) or regionY)
    }

    /**
     * Check if XTEA keys have been loaded.
     */
    fun isLoaded(): Boolean = loaded

    /**
     * Get the total number of loaded region keys.
     */
    fun size(): Int = keys.size

    /**
     * Check if a region has XTEA keys (i.e., is encrypted).
     */
    fun hasKeys(regionId: Int): Boolean {
        val keyArray = keys[regionId] ?: return false
        return !keyArray.all { it == 0 }
    }

    /**
     * Check if a region has XTEA keys by coordinates.
     */
    fun hasKeys(regionX: Int, regionY: Int): Boolean {
        return hasKeys((regionX shl 8) or regionY)
    }

    /**
     * Get all region IDs that have XTEA keys.
     */
    fun getEncryptedRegionIds(): Set<Int> {
        return keys.filterValues { keyArray -> !keyArray.all { it == 0 } }.keys
    }

    fun put(regionId: Int, regionKeys: IntArray) {
        keys = keys + (regionId to regionKeys.copyOf())
    }

    /**
     * Clear loaded keys (for testing or reloading).
     */
    fun clear() {
        keys = emptyMap()
        loaded = false
    }
}
