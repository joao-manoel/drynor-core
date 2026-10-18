package org.drynor.tools.engine.render

import org.drynor.tools.cache.model.ModelLoader
import org.drynor.tools.cache.texture.TextureLoader
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_CULL_FACE
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_LEQUAL
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.GL_VIEWPORT
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDepthFunc
import org.lwjgl.opengl.GL11.glDepthMask
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetInteger
import org.lwjgl.opengl.GL11.glGetIntegerv
import org.lwjgl.opengl.GL11.glIsEnabled
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.system.MemoryUtil
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ObjIconStack {
    NONE,
    WHEN_MULTIPLE,
    ALWAYS
}

data class ObjIconRequest(
    val obj: Int,
    val amount: Int = 1,
    val outline: Int = 0,
    val shadow: Int = 0,
    val stack: ObjIconStack = ObjIconStack.WHEN_MULTIPLE,
    val zoomedIn: Boolean = false
)

class ObjIcon(val textureId: Int, val pixels: IntArray)

fun interface ObjIconFont {
    fun draw(canvas: Canvas2D, text: String, x: Int, y: Int, colour: Int, shadow: Int)
}

class ObjIcons(
    private val cache: Cache,
    private val font: ObjIconFont? = null
) {
    var modelSource: (Int) -> ModelDefinition? = { ModelLoader.loadDefinition(cache, it) }

    private val decoder = ItemDecoder()
    private val definitions: Array<ItemDefinition> = decoder.create(decoder.size(cache) + 1)
    private val decoded = BooleanArray(definitions.size)

    private val icons = HashMap<ObjIconRequest, ObjIcon?>()

    private val target = RenderTarget()
    private var colourShader: ShaderProgram? = null
    private var texturedShader: ShaderProgram? = null
    private var canvas: Canvas2D? = null
    private var texturesReady = false

    val count: Int get() = definitions.size

    fun definition(obj: Int): ItemDefinition? {
        if (obj < 0 || obj >= definitions.size) {
            return null
        }
        if (!decoded[obj]) {
            decoded[obj] = true
            try {
                decoder.load(definitions, cache, obj)
            } catch (e: Exception) {
                System.err.println("Failed to decode obj $obj: ${e.message}")
            }
        }
        return definitions[obj]
    }

    fun isCached(request: ObjIconRequest): Boolean = icons.containsKey(request)

    fun icon(obj: Int): ObjIcon? = icon(ObjIconRequest(obj))

    fun icon(request: ObjIconRequest): ObjIcon? {
        if (icons.containsKey(request)) {
            return icons[request]
        }
        val built = sprite(request)?.let { ObjIcon(upload(it), it) }
        icons[request] = built
        return built
    }

    fun cleanup() {
        icons.values.forEach { icon ->
            if (icon != null) {
                glDeleteTextures(icon.textureId)
            }
        }
        icons.clear()
        colourShader?.cleanup()
        texturedShader?.cleanup()
        canvas?.cleanup()
        target.cleanup()
        colourShader = null
        texturedShader = null
        canvas = null
    }

    private fun sprite(request: ObjIconRequest): IntArray? = pixels(definition(request.obj) ?: return null, request)

    fun pixels(base: ItemDefinition, request: ObjIconRequest): IntArray? {
        val definition = stackAlternate(base, request.amount)
        val model = iconModel(definition) ?: return null

        var pixels = render(model, request) ?: return null
        if (request.outline >= 1) {
            pixels = outline(pixels, OUTLINE_DARK)
            if (request.outline >= 2) {
                pixels = outline(pixels, OUTLINE_LIGHT)
            }
        }
        if (request.shadow != 0) {
            shadow(pixels, request.shadow)
        }

        val composed = IntArray(WIDTH * HEIGHT)
        template(definition, request)?.let { blend(composed, it) }
        blend(composed, pixels)
        if (definition.notedTemplateId != -1) {
            noteFace(definition)?.let { blend(composed, it) }
        }
        amount(composed, definition, request)
        for (index in composed.indices) {
            composed[index] = if (composed[index] and RGB == 0) 0 else OPAQUE or composed[index]
        }
        return composed
    }

    private fun template(definition: ItemDefinition, request: ObjIconRequest): IntArray? {
        val underlying = when {
            definition.lendTemplateId != -1 -> definition.lendId
            definition.singleNoteTemplateId != -1 -> definition.singleNoteId
            else -> return null
        }
        return sprite(
            ObjIconRequest(underlying, request.amount, request.outline, request.shadow, ObjIconStack.NONE)
        )
    }

    private fun noteFace(definition: ItemDefinition): IntArray? =
        sprite(ObjIconRequest(definition.noteId, NOTE_FACE_AMOUNT, 1, 0, ObjIconStack.NONE, zoomedIn = true))

    private fun amount(pixels: IntArray, definition: ItemDefinition, request: ObjIconRequest) {
        val writer = font ?: return
        val stacked = definition.stackable == 1 || request.amount != 1
        val write = request.stack == ObjIconStack.ALWAYS ||
            (request.stack == ObjIconStack.WHEN_MULTIPLE && stacked && request.amount != -1)
        if (!write) {
            return
        }
        val text = formatAmount(request.amount)
        val drawn = renderText(writer, text) ?: return
        blendAlpha(pixels, drawn)
    }

    private fun blend(destination: IntArray, source: IntArray) {
        for (index in destination.indices) {
            if (source[index] != 0) {
                destination[index] = source[index]
            }
        }
    }

    private fun blendAlpha(destination: IntArray, source: IntArray) {
        for (index in destination.indices) {
            val pixel = source[index]
            val alpha = pixel ushr 24
            if (alpha == 0) {
                continue
            }
            if (alpha == 0xff) {
                destination[index] = pixel
                continue
            }
            val under = destination[index]
            val red = ((pixel ushr 16 and 0xff) * alpha + (under ushr 16 and 0xff) * (255 - alpha)) / 255
            val green = ((pixel ushr 8 and 0xff) * alpha + (under ushr 8 and 0xff) * (255 - alpha)) / 255
            val blue = ((pixel and 0xff) * alpha + (under and 0xff) * (255 - alpha)) / 255
            destination[index] = OPAQUE or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun stackAlternate(definition: ItemDefinition, amount: Int): ItemDefinition {
        val ids = definition.stackIds ?: return definition
        val amounts = definition.stackAmounts ?: return definition
        if (amount <= 1) {
            return definition
        }
        var alternate = -1
        for (index in 0 until minOf(ids.size, amounts.size)) {
            if (amounts[index] != 0 && amount >= amounts[index]) {
                alternate = ids[index]
            }
        }
        if (alternate == -1) {
            return definition
        }
        return definition(alternate) ?: definition
    }

    private fun iconModel(definition: ItemDefinition): IconModel? {
        val note = definition.notedTemplateId != -1
        val templateId = when {
            note -> definition.notedTemplateId
            definition.lendTemplateId != -1 -> definition.lendTemplateId
            definition.singleNoteTemplateId != -1 -> definition.singleNoteTemplateId
            else -> -1
        }
        if (templateId == -1) {
            return IconModel(definition, definition)
        }
        val template = definition(templateId) ?: return null
        if (note) {
            return IconModel(template, template)
        }
        val underlyingId = if (definition.lendTemplateId != -1) definition.lendId else definition.singleNoteId
        return IconModel(template, definition(underlyingId) ?: definition)
    }

    private class IconModel(camera: ItemDefinition, colours: ItemDefinition) {
        val modelId = camera.modelId
        val zoom = camera.graphicScale
        val rotationX = camera.graphicPitch
        val rotationY = camera.graphicCameraRoll
        val rotationZ = camera.graphicCameraYaw
        val offsetX = camera.graphicTranslateX
        val offsetY = camera.graphicTranslateY
        val scaleX = camera.floorScaleX
        val scaleY = camera.floorScaleZ
        val scaleZ = camera.floorScaleY
        val ambient = AMBIENT_BASE + camera.ambience
        val recolourSrc = colours.originalColours
        val recolourDst = colours.modifiedColours
        val retextureSrc = colours.originalTextureColours
        val retextureDst = colours.modifiedTextureColours
    }

    private fun render(model: IconModel, request: ObjIconRequest): IntArray? {
        val definition = modelSource(model.modelId) ?: return null
        if (definition.faceCount == 0) {
            return null
        }
        prepare()
        val colour = colourShader ?: return null
        val textured = texturedShader ?: return null
        if (!target.resize(WIDTH, HEIGHT)) {
            return null
        }
        val mesh = ModelLoader.createTexturedModel(substitute(definition, model), model.ambient, cache, hideMarkerFaces = false)
            ?: return null

        val zoom = zoom(model.zoom, request)
        val projection = Matrix4f().setFrustum(
            NEAR * -CENTRE_X / FOCAL, NEAR * (WIDTH - CENTRE_X) / FOCAL,
            NEAR * -(HEIGHT - CENTRE_Y) / FOCAL, NEAR * CENTRE_Y / FOCAL,
            NEAR, FAR
        )
        val view = Matrix4f()
        val transform = transform(model, zoom, minY(definition, model))

        val previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING)
        val viewport = IntArray(4)
        glGetIntegerv(GL_VIEWPORT, viewport)
        val scissored = glIsEnabled(GL_SCISSOR_TEST)

        target.begin()
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        mesh.colorBatch?.let { batch ->
            colour.bind()
            uniforms(colour, view, projection, transform)
            batch.render()
            colour.unbind()
        }
        if (mesh.textureBatches.isNotEmpty()) {
            textured.bind()
            uniforms(textured, view, projection, transform)
            textured.setUniform("textureSampler", 0)
            textured.setUniform("useTexture", true)
            for (batch in mesh.textureBatches) {
                TextureLoader.bindTexture(TextureLoader.getTextureForMaterial(cache, batch.textureId), 0)
                batch.mesh.render()
            }
            TextureLoader.unbindTexture()
            textured.unbind()
        }

        val pixels = readPixels(opaque = true)
        mesh.cleanup()

        glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        if (scissored) {
            glEnable(GL_SCISSOR_TEST)
        }
        return pixels
    }

    private fun renderText(writer: ObjIconFont, text: String): IntArray? {
        prepare()
        val canvas = canvas ?: return null
        if (!target.resize(WIDTH, HEIGHT)) {
            return null
        }
        val previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING)
        val viewport = IntArray(4)
        glGetIntegerv(GL_VIEWPORT, viewport)
        val scissored = glIsEnabled(GL_SCISSOR_TEST)

        target.begin()
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        canvas.begin(WIDTH, HEIGHT)
        writer.draw(canvas, text, 0, AMOUNT_BASELINE, AMOUNT_COLOUR, AMOUNT_SHADOW)
        canvas.end()
        val pixels = readPixels(opaque = false)

        glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        if (scissored) {
            glEnable(GL_SCISSOR_TEST)
        }
        return pixels
    }

    // The client's item matrix reads Rz -> Ry -> translate -> Rx, each step in the space the last left.
    private fun transform(model: IconModel, zoom: Int, minY: Int): Matrix4f {
        val angleX = model.rotationX shl ANGLE_SHIFT
        val translateY = fixed(zoom, sin(theta(angleX))) - minY / 2 + (model.offsetY shl OFFSET_SHIFT)
        val translateZ = fixed(zoom, cos(theta(angleX))) + (model.offsetY shl OFFSET_SHIFT)
        return Matrix4f()
            .scale(1f, -1f, -1f)
            .rotateX(theta(angleX))
            .translate((model.offsetX shl OFFSET_SHIFT).toFloat(), translateY.toFloat(), translateZ.toFloat())
            .rotateY(theta(model.rotationY shl ANGLE_SHIFT))
            .rotateZ(theta(-model.rotationZ shl ANGLE_SHIFT))
            .scale(
                RS_UNITS * model.scaleX / SCALE_UNIT,
                -RS_UNITS * model.scaleY / SCALE_UNIT,
                -RS_UNITS * model.scaleZ / SCALE_UNIT
            )
    }

    private fun zoom(scale: Int, request: ObjIconRequest): Int = when {
        request.zoomedIn -> (ZOOMED_IN * scale).toInt() shl OFFSET_SHIFT
        request.outline == 2 -> (scale * OUTLINE_ZOOM).toInt() shl OFFSET_SHIFT
        else -> scale shl OFFSET_SHIFT
    }

    private fun minY(definition: ModelDefinition, model: IconModel): Int {
        var minimum = Int.MAX_VALUE
        for (index in 0 until definition.vertexCount) {
            val y = definition.vertexY[index] * model.scaleY / SCALE_UNIT
            if (y < minimum) {
                minimum = y
            }
        }
        return if (minimum == Int.MAX_VALUE) 0 else minimum
    }

    private fun substitute(definition: ModelDefinition, model: IconModel): ModelDefinition {
        val colours = recoloured(definition.faceColors, model.recolourSrc, model.recolourDst)
        val textures = definition.faceTextures?.let {
            recoloured(it, model.retextureSrc, model.retextureDst)
        }
        if (colours == null && textures == null) {
            return definition
        }
        return definition.copy(
            faceColors = colours ?: definition.faceColors,
            faceTextures = textures ?: definition.faceTextures
        )
    }

    private fun recoloured(values: ShortArray, source: ShortArray?, destination: ShortArray?): ShortArray? {
        if (source == null || destination == null || source.isEmpty()) {
            return null
        }
        var replaced: ShortArray? = null
        for (index in values.indices) {
            for (pair in source.indices) {
                if (values[index] == source[pair] && pair < destination.size) {
                    val target = replaced ?: values.copyOf().also { replaced = it }
                    target[index] = destination[pair]
                }
            }
        }
        return replaced
    }

    private fun uniforms(shader: ShaderProgram, view: Matrix4f, projection: Matrix4f, transform: Matrix4f) {
        shader.setUniform("view", view)
        shader.setUniform("projection", projection)
        shader.setUniform("model", transform)
        shader.setUniform("ambientLight", AMBIENT_LIGHT)
        shader.setUniform("lightDirection", LIGHT_DIRECTION)
        shader.setUniform("lightColor", LIGHT_COLOUR)
    }

    private fun prepare() {
        if (!texturesReady) {
            texturesReady = true
            TextureLoader.init(cache)
        }
        if (colourShader == null) {
            colourShader = ShaderProgram.createWithUniforms(
                source("/shaders/color.vert"), source("/shaders/color.frag"),
                "model", "view", "projection", "ambientLight", "lightDirection", "lightColor"
            )
        }
        if (texturedShader == null) {
            texturedShader = ShaderProgram.createWithUniforms(
                source("/shaders/textured.vert"), source("/shaders/textured.frag"),
                "model", "view", "projection", "ambientLight", "lightDirection", "lightColor",
                "textureSampler", "useTexture"
            )
        }
        if (canvas == null && font != null) {
            canvas = Canvas2D(source("/shaders/ui.vert"), source("/shaders/ui.frag"))
        }
    }

    private fun readPixels(opaque: Boolean): IntArray {
        val buffer = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4)
        try {
            glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            val pixels = IntArray(WIDTH * HEIGHT)
            for (y in 0 until HEIGHT) {
                val row = (HEIGHT - 1 - y) * WIDTH * 4
                for (x in 0 until WIDTH) {
                    val offset = row + x * 4
                    val alpha = buffer.get(offset + 3).toInt() and 0xff
                    val rgb = ((buffer.get(offset).toInt() and 0xff) shl 16) or
                        ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
                        (buffer.get(offset + 2).toInt() and 0xff)
                    // The client treats a pure black pixel as empty; the final sprite keeps that rule.
                    pixels[y * WIDTH + x] = when {
                        alpha == 0 -> 0
                        opaque -> if (rgb == 0) 0 else OPAQUE or rgb
                        else -> (alpha shl 24) or rgb
                    }
                }
            }
            return pixels
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun upload(pixels: IntArray): Int {
        val buffer = MemoryUtil.memAlloc(pixels.size * 4)
        try {
            for (pixel in pixels) {
                buffer.put((pixel ushr 16 and 0xff).toByte())
                buffer.put((pixel ushr 8 and 0xff).toByte())
                buffer.put((pixel and 0xff).toByte())
                buffer.put((pixel ushr 24 and 0xff).toByte())
            }
            buffer.flip()
            val textureId = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, textureId)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glBindTexture(GL_TEXTURE_2D, 0)
            return textureId
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun source(resource: String): String =
        ObjIcons::class.java.getResourceAsStream(resource)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("shader $resource is missing.")

    companion object {
        const val WIDTH = 36
        const val HEIGHT = 32

        private const val RGB = 0xffffff
        private const val OPAQUE = 0xff shl 24

        private const val FOCAL = 512f
        // The client's projection centre is pixel (16, 16) of the 36x32 canvas, not its middle.
        private const val CENTRE_X = 16f
        private const val CENTRE_Y = 16f
        private const val NEAR = 50f

        // Stands in for the client's 2^31 far plane, which no depth buffer can hold usefully.
        private const val FAR = 100000f

        private const val RS_UNITS = 128f
        private const val SCALE_UNIT = 128
        private const val ANGLE_SHIFT = 3
        private const val OFFSET_SHIFT = 2
        private const val TURN_UNITS = 16384f
        private const val FIXED_BITS = 14

        private const val ZOOMED_IN = 1.5
        private const val OUTLINE_ZOOM = 1.04

        private const val OUTLINE_DARK = -16777214
        private const val OUTLINE_LIGHT = -1

        private const val AMBIENT_BASE = 64
        private const val NOTE_FACE_AMOUNT = 10

        private const val AMOUNT_BASELINE = 9
        private const val AMOUNT_COLOUR = -256
        private const val AMOUNT_SHADOW = -16777215

        private val AMBIENT_LIGHT = Vector3f(0.55f, 0.55f, 0.55f)

        private val LIGHT_DIRECTION = Vector3f(50f, -10f, -50f).normalize()
        private val LIGHT_COLOUR = Vector3f(1f, 1f, 1f)

        private fun theta(angle: Int): Float =
            ((angle and (TURN_UNITS.toInt() - 1)) / TURN_UNITS) * 2f * PI.toFloat()

        private fun fixed(value: Int, ratio: Float): Int =
            (value * (TURN_UNITS * ratio).toInt()) shr FIXED_BITS

        fun formatAmount(amount: Int): String = when {
            amount < 100000 -> "<col=ffff00>$amount</col>"
            amount < 10000000 -> "<col=ffffff>${amount / 1000}K</col>"
            else -> "<col=00ff80>${amount / 1000000}M</col>"
        }

        fun outline(pixels: IntArray, colour: Int): IntArray {
            val outlined = IntArray(pixels.size)
            var index = 0
            for (row in 0 until HEIGHT) {
                for (column in 0 until WIDTH) {
                    var pixel = pixels[index]
                    if (pixel == 0) {
                        if (column > 0 && pixels[index - 1] != 0) {
                            pixel = colour
                        } else if (row > 0 && pixels[index - WIDTH] != 0) {
                            pixel = colour
                        } else if (column < WIDTH - 1 && pixels[index + 1] != 0) {
                            pixel = colour
                        } else if (row < HEIGHT - 1 && pixels[index + WIDTH] != 0) {
                            pixel = colour
                        }
                    }
                    outlined[index++] = pixel
                }
            }
            return outlined
        }

        fun shadow(pixels: IntArray, colour: Int) {
            for (row in HEIGHT - 1 downTo 1) {
                val start = row * WIDTH
                for (column in WIDTH - 1 downTo 1) {
                    if (pixels[start + column] == 0 && pixels[start + column - 1 - WIDTH] != 0) {
                        pixels[start + column] = colour
                    }
                }
            }
        }
    }
}
