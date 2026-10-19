package org.drynor.tools.osrs

/** Evidence: gielenor-client ebef1dc918e99fbf3ea6fca3b1d7e8c728406a8b, Class451/Class375. */
internal object GielenorVisualProfile {
    const val CLIENT_COMMIT = "ebef1dc918e99fbf3ea6fca3b1d7e8c728406a8b"

    fun runtimeMaterial(rawMaterial: Int): Int {
        require(rawMaterial in 0..65535)
        return when (rawMaterial) {
            10 -> 8
            7 -> 42
            59 -> 2710
            else -> rawMaterial
        }
    }

    fun forceOsrsSkeleton(sequenceId: Int): Boolean = sequenceId >= 28568 || sequenceId == 26657 || sequenceId == 26658

    fun sourceBaseId(rawBase: Int, forced: Boolean): Int {
        require(rawBase in 0..65535)
        return if (rawBase == 0 && !forced) 0 else rawBase + 5000
    }

    fun effectiveUnderlayFile(rawTileId: Int): Int {
        require(rawTileId in 1..174)
        val requested = rawTileId - 1 + 1000
        return if (requested == 1026) requested else requested - 1000
    }

    fun effectiveInfernoOverlayFile(rawTileId: Int): Int {
        // Both files are explicitly exempt from the native-floor fallback in Class375.getOverlay.
        require(rawTileId == 72 || rawTileId == 152) { "Overlay outside the verified Inferno profile: $rawTileId" }
        return rawTileId - 1 + 1000
    }
}
