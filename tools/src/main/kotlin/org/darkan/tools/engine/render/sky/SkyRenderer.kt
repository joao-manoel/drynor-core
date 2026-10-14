package org.darkan.tools.engine.render.sky

import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.ShaderProgram
import org.joml.Vector3f
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_CULL_FACE
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK
import org.lwjgl.opengl.GL11.GL_LEQUAL
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_REPEAT
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDepthFunc
import org.lwjgl.opengl.GL11.glDepthMask
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetBoolean
import org.lwjgl.opengl.GL11.glIsEnabled
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glUniform2f
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.system.MemoryUtil
import world.gregs.voidps.cache.Cache

/**
 * Draws the RS727 sky.
 *
 * Appearance is taken from the reference client's `graphics/light/Skybox.kt`,
 * the implementation is not: the client blits a CPU graphic across the whole
 * viewport every frame, this builds one texture per material and then costs a
 * single attribute-less draw call.
 *
 * ### Where it goes in a frame
 *
 * Draw it **after** opaque scene geometry and before transparent geometry / UI.
 * The full-screen triangle sits exactly on the far plane with depth writes off
 * and a `GL_LEQUAL` test, so every pixel the scene already covers is rejected
 * by the depth test and never shades. Drawing it first would work too, but
 * would shade the whole viewport.
 *
 * ```kotlin
 * // once
 * val sky = SkyRenderer(cache)
 * sky.init()
 *
 * // whenever the map environment changes
 * sky.settings = SkySettings(materialId = ..., backgroundColor = fogColour)
 *
 * // per frame, after the opaque pass
 * sky.render(camera, window.width, window.height)
 * ```
 *
 * @param cache game cache used to build skybox material textures; may be null,
 *        in which case only the solid-colour fallback is available
 */
class SkyRenderer(private val cache: Cache? = null) {

    /**
     * How the renderer puts back the GL state it changes.
     *
     * [SkyStateRestore.QUERY] is correct anywhere but costs a driver round trip; see the
     * enum for why that matters when the sky is the last pass of a heavy frame.
     */
    var stateRestore: SkyStateRestore = SkyStateRestore.QUERY

    /** Sky parameters used by the next [render] call. Cheap to replace. */
    var settings: SkySettings = SkySettings.DEFAULT

    private var backgroundShader: ShaderProgram? = null
    private var vaoId: Int = 0

    /** materialId -> built texture. Built lazily, kept for the renderer's life. */
    private val textures = HashMap<Int, SkyMaterial?>()

    private val solidColor = Vector3f()
    private val topColor = Vector3f()
    private val bottomColor = Vector3f()

    // ShaderProgram has no vec2 setter, so the two-component uniforms are
    // resolved and written here directly.
    private var uSkyViewport = -1
    private var uSkyOffset = -1

    /**
     * Compile shaders and create the empty VAO. Requires a current GL context.
     */
    fun init() {
        if (backgroundShader != null) {
            return
        }
        val background = ShaderProgram.createWithUniforms(
            source("/shaders/sky_background.vert"),
            source("/shaders/sky_background.frag"),
            "skyMode", "skyColor", "skyTopColor", "skyBottomColor", "skyAlpha", "skySampler"
        )
        uSkyViewport = glGetUniformLocation(background.id, "skyViewport")
        uSkyOffset = glGetUniformLocation(background.id, "skyOffset")
        backgroundShader = background
        // Core profile forbids drawing with no VAO bound, even for an
        // attribute-less shader that only reads gl_VertexID.
        vaoId = glGenVertexArrays()
    }

    /**
     * Draw the sky for [camera]'s orientation.
     *
     * Only the camera's yaw and pitch matter: the sky is infinitely far away,
     * so its position and the projection matrix never enter the math.
     */
    fun render(camera: Camera, viewportWidth: Int, viewportHeight: Int) {
        render(camera.yaw, camera.pitch, viewportWidth, viewportHeight)
    }

    /**
     * Lower level entry point for viewers that do not drive a [Camera].
     *
     * @param yawDegrees camera heading in degrees, matching [Camera.yaw]
     * @param pitchDegrees camera pitch in degrees, matching [Camera.pitch]
     */
    fun render(
        yawDegrees: Float,
        pitchDegrees: Float,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val shader = backgroundShader ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return
        }

        val width = viewportWidth.toFloat()
        val height = viewportHeight.toFloat()

        // The client's scroll math, in floats. rotationX/rotationY are RS angle
        // units (16384 == a full turn) and are negated relative to the camera so
        // the sky reads as world-locked: turning right slides the sky left.
        val rotationX = wrap(-toAngleUnits(yawDegrees) + settings.rotation.toFloat(), ANGLE_UNITS)
        val rotationY = -toAngleUnits(pitchDegrees)
        val xOffset = wrap(rotationX * height / 4096f + (width - height) * 0.5f, height)
        val yOffset = wrap(rotationY * height / -4096f, height)

        val material = if (settings.materialId >= 0) material(settings.materialId) else null
        val mode = when {
            material == null -> MODE_SOLID
            settings.backgroundMode == SkyBackgroundMode.COLOR_TRANSITION -> MODE_TRANSITION
            else -> MODE_REPEAT
        }

        val alpha = settings.alpha.coerceIn(0f, 1f)
        val blended = alpha < 1f

        // Save the pieces of GL state this pass changes. glIsEnabled and glGetBoolean are
        // synchronous, so a caller that draws the sky at the end of a heavy frame can opt
        // out of the readback (see SkyStateRestore).
        val query = stateRestore == SkyStateRestore.QUERY
        val cullWasEnabled = if (query) glIsEnabled(GL_CULL_FACE) else true
        val blendWasEnabled = if (query) glIsEnabled(GL_BLEND) else false
        val depthWasEnabled = if (query) glIsEnabled(GL_DEPTH_TEST) else true
        val depthWriteWasEnabled = if (query) glGetBoolean(GL_DEPTH_WRITEMASK) else true

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        if (blended) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        } else {
            glDisable(GL_BLEND)
        }

        glBindVertexArray(vaoId)
        shader.bind()
        shader.setUniform("skyMode", mode)
        shader.setUniform("skyAlpha", alpha)
        shader.setUniform("skySampler", 0)
        unpack(settings.backgroundColor, solidColor)
        shader.setUniform("skyColor", solidColor)

        if (material != null) {
            topColor.set(material.topColor)
            bottomColor.set(material.bottomColor)
            if (!material.topOpaque) {
                topColor.set(solidColor)
            }
            if (!material.bottomOpaque) {
                bottomColor.set(solidColor)
            }
            shader.setUniform("skyTopColor", topColor)
            shader.setUniform("skyBottomColor", bottomColor)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, material.textureId)
        }
        glUniform2f(uSkyViewport, width, height)
        glUniform2f(uSkyOffset, xOffset, yOffset)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        shader.unbind()
        glBindVertexArray(0)
        glBindTexture(GL_TEXTURE_2D, 0)

        // Restore.
        glDepthMask(depthWriteWasEnabled)
        if (!depthWasEnabled) {
            glDisable(GL_DEPTH_TEST)
        }
        if (cullWasEnabled) {
            glEnable(GL_CULL_FACE)
        }
        if (blendWasEnabled) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        } else {
            glDisable(GL_BLEND)
        }
    }

    /**
     * Build (or fetch) the GL texture for a skybox material.
     *
     * Uses the same procedural texture pipeline the model renderer uses, so a
     * skybox material costs exactly one generation regardless of how many
     * regions reference it. Failures are cached as null so a broken material is
     * not retried every frame.
     */
    private fun material(materialId: Int): SkyMaterial? {
        if (materialId < 0) {
            return null
        }
        if (textures.containsKey(materialId)) {
            return textures[materialId]
        }
        val built = buildMaterial(materialId)
        textures[materialId] = built
        return built
    }

    private fun buildMaterial(materialId: Int): SkyMaterial? {
        val cache = this.cache ?: return null
        val pixels = try {
            TextureLoader.getTexturePixelsForMaterial(cache, materialId)
        } catch (e: Exception) {
            System.err.println("SkyRenderer: failed to generate skybox material $materialId: ${e.message}")
            null
        } ?: return null

        val (argb, width, height) = pixels
        if (argb.isEmpty() || width <= 0 || height <= 0) {
            return null
        }

        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        // No mipmaps: the fragment shader wraps the coordinate itself, and
        // mipmapped derivatives would bleed a seam across the tile boundary.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            // Row 0 stays at v = 0: the fragment shader addresses the graphic in
            // top-down window space, matching the client's blit.
            for (i in 0 until (width * height)) {
                val pixel = argb[i]
                buffer.put(((pixel shr 16) and 0xFF).toByte())
                buffer.put(((pixel shr 8) and 0xFF).toByte())
                buffer.put((pixel and 0xFF).toByte())
                buffer.put(((pixel shr 24) and 0xFF).toByte())
            }
            buffer.flip()
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }
        glBindTexture(GL_TEXTURE_2D, 0)

        val first = argb[0]
        val last = argb[argb.size - 1]
        return SkyMaterial(
            textureId = textureId,
            topColor = unpack(first, Vector3f()),
            bottomColor = unpack(last, Vector3f()),
            // The client only paints the transition bands when the sampled
            // pixel carries alpha (`srcBackgroundColor and 0xffffff.inv() != 0`).
            topOpaque = ((first shr 24) and 0xFF) != 0,
            bottomOpaque = ((last shr 24) and 0xFF) != 0
        )
    }

    /** Drops cached material textures; call after the texture cache is cleared. */
    fun clearTextureCache() {
        textures.values.forEach { it?.let { m -> glDeleteTextures(m.textureId) } }
        textures.clear()
    }

    fun cleanup() {
        clearTextureCache()
        backgroundShader?.cleanup()
        backgroundShader = null
        if (vaoId != 0) {
            glDeleteVertexArrays(vaoId)
            vaoId = 0
        }
    }

    private fun source(path: String): String =
        SkyRenderer::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: throw RuntimeException("Could not load shader: $path")

    private class SkyMaterial(
        val textureId: Int,
        val topColor: Vector3f,
        val bottomColor: Vector3f,
        val topOpaque: Boolean,
        val bottomOpaque: Boolean
    )

    companion object {
        private const val ANGLE_UNITS = 16384f
        private const val MODE_SOLID = 0
        private const val MODE_REPEAT = 1
        private const val MODE_TRANSITION = 2

        /** Degrees to RS angle units (16384 per full turn). */
        private fun toAngleUnits(degrees: Float): Float = degrees / 360f * ANGLE_UNITS

        private fun wrap(value: Float, range: Float): Float {
            val remainder = value % range
            return if (remainder < 0f) remainder + range else remainder
        }

        /** Unpacks 0xRRGGBB into 0..1 components. */
        fun unpack(color: Int, out: Vector3f): Vector3f = out.set(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f
        )
    }
}
