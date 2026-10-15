package org.drynor.tools.engine.render.sky

import org.joml.Vector3f

/**
 * How the skybox material is spread across the viewport.
 *
 * Mirrors `BackgroundMode` in the reference client
 * (`config/skybox/BackgroundMode.kt`).
 */
enum class SkyBackgroundMode {
    /** The material graphic is tiled in both axes (`drawRepeatingBackground`). */
    SINGLE_COLOR,

    /**
     * A single vertical band of the material, with the material's first pixel
     * colour filling everything above it and its last pixel colour filling
     * everything below (`drawTransitioningBackground`).
     */
    COLOR_TRANSITION;

    companion object {
        /** Decodes the cache's `BackgroundMode` ordinal, defaulting to [SINGLE_COLOR]. */
        fun of(value: Int): SkyBackgroundMode =
            if (value == 1) COLOR_TRANSITION else SINGLE_COLOR
    }
}

/**
 * How [SkyRenderer] restores the GL state it touches: depth test, depth write mask,
 * face culling and blending.
 */
enum class SkyStateRestore {
    /**
     * Read the four states back from the driver and restore exactly what was found.
     * Correct anywhere, but `glIsEnabled` and `glGetBoolean` are synchronous: issued after
     * a few hundred queued draw calls they flush the pipeline and block the CPU until the
     * GPU has caught up. In the map viewer that showed up as ~5.5 ms attributed to a pass
     * that is one triangle.
     */
    QUERY,

    /**
     * Skip the readback and restore the scene's steady state instead: depth test on,
     * depth writes on, back-face culling on, blending off. Use it when the caller knows
     * that is the state the sky pass was entered with.
     */
    ASSUME_SCENE_DEFAULTS
}

/**
 * One lens-flare style sun billboard.
 *
 * **Not rendered.** [SkyRenderer] ignores [SkySettings.suns] entirely. The
 * shape of the data is settled here so the wiring does not have to change
 * later, but nothing in this cache can populate it: config index 2 archive 30
 * (`SharedConfigsType.SUN`) is absent, and every skybox type in the cache
 * decodes with a null `sunDefinitionIds`, so no skybox ever resolves a sun.
 * Rather than invent the visuals, the pass is left unwritten.
 *
 * For reference, the client (`config/sun/Sun.kt` → `calculateProjection`)
 * projects a point very far along the sun's direction and blits a square graphic
 * of `positionOffset` pixels centred on the projected position.
 *
 * @param direction unit vector in engine world space pointing *at* the sun
 * @param materialId cache material used for the graphic, or -1 for none
 * @param sizePixels on-screen edge length of the billboard, in pixels
 * @param alpha opacity multiplier, 0..1
 */
data class SkySun(
    val direction: Vector3f,
    val materialId: Int,
    val sizePixels: Float,
    val alpha: Float = 1.0f
)

/**
 * Everything [SkyRenderer] needs for one frame of sky.
 *
 * Deliberately independent of the cache environment decoder types: the map
 * environment record is adapted into this at wiring time.
 *
 * @param materialId cache material id for the panoramic graphic, or -1 for a
 *        flat [backgroundColor] fill (the common case)
 * @param backgroundMode how that material is spread across the viewport
 * @param rotation the skybox's own yaw offset in RS angle units (0..16383),
 *        i.e. the `skyboxAngle` argument of `Skybox.renderSkybox`
 * @param backgroundColor packed 0xRRGGBB fog / background colour
 * @param alpha global opacity, used when cross-fading between two skyboxes
 * @param suns sun billboards; see [SkySun] — accepted but never drawn, because
 *        this cache carries no sun configs at all
 */
data class SkySettings(
    val materialId: Int = -1,
    val backgroundMode: SkyBackgroundMode = SkyBackgroundMode.SINGLE_COLOR,
    val rotation: Int = 0,
    val backgroundColor: Int = DEFAULT_FOG_COLOUR,
    val alpha: Float = 1.0f,
    val suns: List<SkySun> = emptyList()
) {
    companion object {
        /** `Environment`'s default `fogColour` (see docs/tools-environment-lighting.md). */
        const val DEFAULT_FOG_COLOUR = 13156520

        val DEFAULT = SkySettings()
    }
}
