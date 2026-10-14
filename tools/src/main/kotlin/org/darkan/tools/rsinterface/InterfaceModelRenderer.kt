package org.darkan.tools.rsinterface

import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.model.TexturedModel
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.render.RenderSystem
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.window.Window
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glScissor
import org.lwjgl.opengl.GL11.glViewport
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import kotlin.math.PI
import kotlin.math.atan

/**
 * Draws model components (type 6) and item icons into their component rectangle.
 *
 * The client renders these through its own scene toolkit with a viewport-local
 * perspective matrix; there is no way to reproduce that exactly without the
 * client's map-region view range and sun matrix, so this reconstructs the same
 * projection from first principles:
 *
 * - RS projects with a focal length of 512 units, so the vertical field of view
 *   is `2 * atan(256 / 512)` and the model sits `graphicScale * 4` RS units in
 *   front of the camera - the same distance the client's model matrix pushes it.
 * - Pitch, roll and yaw are the cache's 0..2047 turn units.
 * - The model is centred on its bounding box, which is what the client achieves
 *   for items via its `-minY / 2` offset and what makes an arbitrary raw model
 *   land inside its viewport.
 *
 * Everything else about the component (clipping, position, size) is decided by
 * [InterfaceRenderer]; this class only owns the GL viewport switch and the
 * matrices.
 */
class InterfaceModelRenderer(
    private val cache: Cache,
    private val window: Window,
    private val renderSystem: RenderSystem,
    private val colorShader: ShaderProgram,
    private val texturedShader: ShaderProgram
) {
    private val models = HashMap<Int, TexturedModel?>()
    private val centres = HashMap<Int, Vector3f?>()

    private val itemDecoder = ItemDecoder()
    private val itemDefinitions: Array<ItemDefinition> = itemDecoder.create(itemDecoder.size(cache) + 1)
    private val itemDecoded = BooleanArray(itemDefinitions.size)

    /**
     * Size of the framebuffer being drawn into, which the viewport and scissor
     * arithmetic below has to flip against. It defaults to the window, but the
     * editor renders the interface into an off-screen target of its own size, and
     * flipping a target-space coordinate against the window's height puts every
     * model viewport somewhere else entirely. Set it to (0, 0) to track the window.
     */
    var targetWidth: Int = 0
    var targetHeight: Int = 0

    private val surfaceWidth: Int get() = if (targetWidth > 0) targetWidth else window.width

    private val surfaceHeight: Int get() = if (targetHeight > 0) targetHeight else window.height

    private val modelMatrix = Matrix4f()
    private val viewMatrix = Matrix4f()
    private val projectionMatrix = Matrix4f()

    /** Decodes (once) and returns an item definition, or null when out of range. */
    fun item(id: Int): ItemDefinition? {
        if (id < 0 || id >= itemDefinitions.size) {
            return null
        }
        if (!itemDecoded[id]) {
            itemDecoded[id] = true
            try {
                itemDecoder.load(itemDefinitions, cache, id)
            } catch (e: Exception) {
                System.err.println("Failed to decode item $id: ${e.message}")
            }
        }
        return itemDefinitions[id]
    }

    /**
     * Renders the model a component resolves to. Returns false when there is
     * nothing to draw, so the caller can fall back to a placeholder.
     *
     * @param clip the clip rectangle in effect, as left/top/right/bottom.
     */
    fun render(state: ComponentState, screenX: Int, screenY: Int, clip: IntArray): Boolean {
        if (state.width <= 0 || state.height <= 0) {
            return false
        }
        val request = resolve(state) ?: return false
        val model = model(request.modelId) ?: return false

        val clipLeft = maxOf(clip[0], screenX)
        val clipTop = maxOf(clip[1], screenY)
        val clipRight = minOf(clip[2], screenX + state.width)
        val clipBottom = minOf(clip[3], screenY + state.height)
        if (clipLeft >= clipRight || clipTop >= clipBottom) {
            return true
        }

        // The 2D pass owns the scissor box; the viewport is ours for this draw.
        val surface = surfaceHeight
        glViewport(screenX, surface - screenY - state.height, state.width, state.height)
        glEnable(GL_SCISSOR_TEST)
        glScissor(clipLeft, surface - clipBottom, clipRight - clipLeft, clipBottom - clipTop)

        // Each component owns its own depth range; without clearing, a model drawn
        // earlier in the frame would occlude one drawn later at the same depth.
        renderSystem.enableDepthWrite()
        glClear(GL_DEPTH_BUFFER_BIT)
        renderSystem.disableBlending()

        val distance = (request.scale * 4).coerceAtLeast(1) * RS_UNIT
        viewMatrix.identity().lookAt(
            0f, 0f, distance,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
        val aspect = state.width.toFloat() / state.height.toFloat()
        projectionMatrix.identity().perspective(FIELD_OF_VIEW, aspect, NEAR_PLANE, distance * 4f + FAR_PADDING)

        val centre = centre(request.modelId) ?: Vector3f()
        modelMatrix.identity()
            .translate(request.translateX * RS_UNIT, -request.translateY * RS_UNIT, 0f)
            .rotateX(turns(request.pitch))
            .rotateY(turns(request.yaw))
            .rotateZ(turns(request.roll))
            .translate(-centre.x, -centre.y, -centre.z)

        renderSystem.enableDepthTest()
        renderSystem.enableModelCullFace()

        val colourBatch = model.colorBatch
        if (colourBatch != null) {
            colorShader.bind()
            colorShader.setUniform("view", viewMatrix)
            colorShader.setUniform("projection", projectionMatrix)
            colorShader.setUniform("model", modelMatrix)
            colorShader.setUniform("ambientLight", AMBIENT)
            colorShader.setUniform("lightDirection", LIGHT_DIRECTION)
            colorShader.setUniform("lightColor", LIGHT_COLOUR)
            colourBatch.render()
            colorShader.unbind()
        }

        if (model.textureBatches.isNotEmpty()) {
            texturedShader.bind()
            texturedShader.setUniform("view", viewMatrix)
            texturedShader.setUniform("projection", projectionMatrix)
            texturedShader.setUniform("model", modelMatrix)
            texturedShader.setUniform("ambientLight", AMBIENT)
            texturedShader.setUniform("lightDirection", LIGHT_DIRECTION)
            texturedShader.setUniform("lightColor", LIGHT_COLOUR)
            texturedShader.setUniform("textureSampler", 0)
            texturedShader.setUniform("useTexture", true)
            for (batch in model.textureBatches) {
                TextureLoader.bindTexture(TextureLoader.getTextureForMaterial(cache, batch.textureId), 0)
                batch.mesh.render()
            }
            TextureLoader.unbindTexture()
            texturedShader.unbind()
        }

        renderSystem.disableCullFace()
        renderSystem.resetFrontFace()
        renderSystem.disableDepthTest()
        renderSystem.setViewport(0, 0, surfaceWidth, surfaceHeight)
        return true
    }

    /**
     * Works out which model a component draws and with what transform. An item
     * set on the component wins over the component's own model, matching the
     * client, which swaps to the item's icon model and its 2D camera angles.
     */
    private fun resolve(state: ComponentState): ModelRequest? {
        if (state.itemId != -1) {
            val definition = item(state.itemId) ?: return null
            return ModelRequest(
                modelId = definition.modelId,
                pitch = definition.graphicPitch,
                roll = definition.graphicCameraYaw,
                yaw = definition.graphicCameraRoll,
                scale = definition.graphicScale,
                translateX = definition.graphicTranslateX,
                translateY = definition.graphicTranslateY
            )
        }
        // Only raw models can be resolved from the cache; player and NPC media
        // types need live entity state the viewer does not have.
        if (state.mediaType != MediaType.RAW_MODEL || state.mediaId <= 0) {
            return null
        }
        return ModelRequest(
            modelId = state.mediaId,
            pitch = state.modelPitch,
            roll = state.modelRoll,
            yaw = state.modelYaw,
            scale = if (state.modelScale > 0) state.modelScale else DEFAULT_SCALE,
            translateX = state.modelOriginX,
            translateY = state.modelOriginY
        )
    }

    /**
     * Loads and caches a model. Failures are cached too - an interface can point
     * at a model that is not in the cache, and retrying every frame would stall
     * the whole viewer.
     */
    private fun model(modelId: Int): TexturedModel? {
        if (models.containsKey(modelId)) {
            return models[modelId]
        }
        val loaded = try {
            ModelLoader.loadTexturedModel(cache, modelId)
        } catch (e: Exception) {
            System.err.println("Failed to load interface model $modelId: ${e.message}")
            null
        }
        models[modelId] = loaded
        return loaded
    }

    /** Centre of the model's bounding box in GL space, cached per model. */
    private fun centre(modelId: Int): Vector3f? {
        if (centres.containsKey(modelId)) {
            return centres[modelId]
        }
        val computed = computeCentre(modelId)
        centres[modelId] = computed
        return computed
    }

    private fun computeCentre(modelId: Int): Vector3f? {
        val definition = ModelLoader.loadDefinition(cache, modelId) ?: return null
        if (definition.vertexCount == 0) {
            return null
        }
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (index in 0 until definition.vertexCount) {
            minX = minOf(minX, definition.vertexX[index])
            maxX = maxOf(maxX, definition.vertexX[index])
            minY = minOf(minY, definition.vertexY[index])
            maxY = maxOf(maxY, definition.vertexY[index])
            minZ = minOf(minZ, definition.vertexZ[index])
            maxZ = maxOf(maxZ, definition.vertexZ[index])
        }
        // Model vertices reach the GPU as (x, -y, -z) scaled by RS_UNIT.
        return Vector3f(
            (minX + maxX) * 0.5f * RS_UNIT,
            -(minY + maxY) * 0.5f * RS_UNIT,
            -(minZ + maxZ) * 0.5f * RS_UNIT
        )
    }

    fun cleanup() {
        models.values.forEach { it?.cleanup() }
        models.clear()
        centres.clear()
    }

    /** A resolved model draw: which model, and the camera angles to draw it at. */
    private class ModelRequest(
        val modelId: Int,
        val pitch: Int,
        val roll: Int,
        val yaw: Int,
        val scale: Int,
        val translateX: Int,
        val translateY: Int
    )

    companion object {
        /** RS world units per GL unit, matching the model loader's scale. */
        private const val RS_UNIT = 1f / 128f

        /** RS angles are 2048 units per full turn. */
        private const val TURN_UNITS = 2048f

        private const val DEFAULT_SCALE = 100

        private const val NEAR_PLANE = 0.05f
        private const val FAR_PADDING = 64f

        /** RS projects with a focal length of 512 units over a 512-unit viewport. */
        private val FIELD_OF_VIEW = (2.0 * atan(256.0 / 512.0)).toFloat()

        private val AMBIENT = Vector3f(0.55f, 0.55f, 0.55f)
        private val LIGHT_DIRECTION = Vector3f(-0.4f, -0.7f, -0.6f).normalize()
        private val LIGHT_COLOUR = Vector3f(1f, 1f, 1f)

        private fun turns(angle: Int): Float = (angle / TURN_UNITS) * 2f * PI.toFloat()
    }
}
