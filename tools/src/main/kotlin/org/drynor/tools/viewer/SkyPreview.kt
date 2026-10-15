package org.drynor.tools.viewer

import org.drynor.tools.cache.texture.TextureLoader
import org.drynor.tools.engine.Engine
import org.drynor.tools.engine.render.Camera
import org.drynor.tools.engine.render.Mesh
import org.drynor.tools.engine.render.sky.SkyBackgroundMode
import org.drynor.tools.engine.render.sky.SkyRenderer
import org.drynor.tools.engine.render.sky.SkySettings
import org.drynor.tools.engine.window.WindowConfig
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP
import org.lwjgl.glfw.GLFW.GLFW_KEY_W
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Standalone harness for [SkyRenderer].
 *
 * Not wired into the tools launcher: run it directly against the installed
 * distribution's lib directory as `org.drynor.tools.viewer.SkyPreviewKt`.
 *
 * Everything is driven by system properties so a screenshot run is
 * reproducible without touching the keyboard:
 *
 * | Property | Meaning |
 * | --- | --- |
 * | `-Dsky.material=<id>` | skybox material id, -1 for the solid fallback |
 * | `-Dsky.mode=repeat\|gradient` | background mode |
 * | `-Dsky.color=0xRRGGBB` | fog / background colour |
 * | `-Dsky.rotation=<0..16383>` | skybox's own yaw offset |
 * | `-Dsky.yaw=<deg>` `-Dsky.pitch=<deg>` | starting camera orientation |
 * | `-Dsky.list=true` | print every skybox config in the cache and exit |
 */
class SkyPreview(private val cache: Cache) : Engine(
    WindowConfig(title = WINDOW_TITLE, width = 1280, height = 720)
) {

    private val camera = Camera(Vector3f(0f, 2.5f, 8f), yaw = -90f, pitch = 0f)
    private lateinit var sky: SkyRenderer
    private var ground: Mesh? = null
    private val boxes = mutableListOf<Pair<Mesh, Matrix4f>>()

    override fun onInit() {
        TextureLoader.init(cache)

        sky = SkyRenderer(cache)
        sky.init()
        sky.settings = settingsFromProperties()

        camera.yaw = property("sky.yaw", -90f)
        camera.pitch = property("sky.pitch", 0f)
        camera.farPlane = 500f

        // A ground plane and a few boxes so the depth rejection is visible: the
        // sky must not paint over any of them.
        ground = Mesh(
            positions = floatArrayOf(
                -60f, 0f, -60f, 60f, 0f, -60f, 60f, 0f, 60f, -60f, 0f, 60f
            ),
            colors = floatArrayOf(
                0.18f, 0.22f, 0.14f, 0.18f, 0.22f, 0.14f,
                0.13f, 0.17f, 0.11f, 0.13f, 0.17f, 0.11f
            ),
            textureCoords = null,
            normals = null,
            indices = intArrayOf(0, 1, 2, 0, 2, 3)
        )
        for (i in 0 until 6) {
            val angle = i * 60.0
            val radius = 9.0
            val matrix = Matrix4f()
                .translate(
                    (radius * Math.cos(Math.toRadians(angle))).toFloat(),
                    1.0f + i * 0.6f,
                    (radius * Math.sin(Math.toRadians(angle))).toFloat()
                )
                .scale(1f, 1f + i * 0.6f, 1f)
            boxes.add(Mesh.createCube(2f) to matrix)
        }

        println("[SKY] settings=${sky.settings}")
    }

    override fun onInput() {
        val speed = 60f * deltaTime
        if (window.isKeyPressed(GLFW_KEY_LEFT) || window.isKeyPressed(GLFW_KEY_A)) camera.yaw -= speed
        if (window.isKeyPressed(GLFW_KEY_RIGHT) || window.isKeyPressed(GLFW_KEY_D)) camera.yaw += speed
        if (window.isKeyPressed(GLFW_KEY_UP) || window.isKeyPressed(GLFW_KEY_W)) camera.pitch += speed
        if (window.isKeyPressed(GLFW_KEY_DOWN) || window.isKeyPressed(GLFW_KEY_S)) camera.pitch -= speed
        camera.pitch = camera.pitch.coerceIn(-89f, 89f)
        camera.lookAt(Vector3f(camera.position).add(directionFromAngles()))
    }

    private fun directionFromAngles(): Vector3f {
        val yaw = Math.toRadians(camera.yaw.toDouble())
        val pitch = Math.toRadians(camera.pitch.toDouble())
        return Vector3f(
            (Math.cos(yaw) * Math.cos(pitch)).toFloat(),
            Math.sin(pitch).toFloat(),
            (Math.sin(yaw) * Math.cos(pitch)).toFloat()
        )
    }

    override fun onRender() {
        val aspect = getAspectRatio()
        val view = camera.getViewMatrixCached()
        val projection = camera.getProjectionMatrix(aspect)

        renderSystem.enableDepthTest()

        simpleShader.bind()
        simpleShader.setUniform("view", view)
        simpleShader.setUniform("projection", projection)
        simpleShader.setUniform("model", Matrix4f())
        ground?.render()
        for ((mesh, model) in boxes) {
            simpleShader.setUniform("model", model)
            mesh.render()
        }
        simpleShader.unbind()

        // Sky goes last: every pixel the scene already covers fails the depth
        // test and is never shaded.
        sky.render(camera, window.width, window.height)
    }

    override fun onCleanup() {
        sky.cleanup()
        ground?.cleanup()
        boxes.forEach { it.first.cleanup() }
    }

    private fun settingsFromProperties(): SkySettings {
        val materialId = property("sky.material", -1)
        val mode = if (System.getProperty("sky.mode", "repeat").startsWith("grad")) {
            SkyBackgroundMode.COLOR_TRANSITION
        } else {
            SkyBackgroundMode.SINGLE_COLOR
        }
        val color = System.getProperty("sky.color")?.let { Integer.decode(it) }
            ?: SkySettings.DEFAULT_FOG_COLOUR
        return SkySettings(
            materialId = materialId,
            backgroundMode = mode,
            rotation = property("sky.rotation", 0),
            backgroundColor = color
        )
    }

    companion object {
        const val WINDOW_TITLE = "Drynor Sky Preview"

        private fun property(name: String, fallback: Int): Int =
            System.getProperty(name)?.toIntOrNull() ?: fallback

        private fun property(name: String, fallback: Float): Float =
            System.getProperty(name)?.toFloatOrNull() ?: fallback

        /**
         * Prints every `SkyboxType` in the cache (index 2, archive 29).
         *
         * Decoding is inlined here rather than added to the cache library
         * because this harness is throwaway; the real decoder belongs to the
         * environment work happening separately.
         */
        fun listSkyboxes(cache: Cache) {
            val files = cache.files(Index.CONFIGS, SKYBOX_ARCHIVE)
            println("[SKY] ${files.size} skybox configs in index 2 archive $SKYBOX_ARCHIVE")
            for (fileId in files) {
                val data = cache.data(Index.CONFIGS, SKYBOX_ARCHIVE, fileId) ?: continue
                val buffer = BufferReader(data)
                var materialId = -1
                var backgroundMode = 0
                var archiveId = -1
                var sunCount = 0
                while (true) {
                    val opcode = buffer.readUnsignedByte()
                    if (opcode == 0) break
                    when (opcode) {
                        1 -> materialId = buffer.readUnsignedShort()
                        2 -> {
                            sunCount = buffer.readUnsignedByte()
                            repeat(sunCount) { buffer.readUnsignedShort() }
                        }
                        3 -> buffer.readUnsignedByte()
                        4 -> backgroundMode = buffer.readUnsignedByte()
                        5 -> archiveId = buffer.readBigSmart()
                        else -> {
                            println("  skybox $fileId: unknown opcode $opcode, stopping")
                            break
                        }
                    }
                }
                println(
                    "  skybox $fileId: material=$materialId mode=$backgroundMode " +
                        "model=$archiveId suns=$sunCount"
                )
            }
        }

        private const val SKYBOX_ARCHIVE = 29
    }
}

fun main() {
    val cache = Cache.get()
    if (System.getProperty("sky.list") != null) {
        SkyPreview.listSkyboxes(cache)
        return
    }
    SkyPreview(cache).run()
}
