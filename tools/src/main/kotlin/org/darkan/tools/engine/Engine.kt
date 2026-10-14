package org.darkan.tools.engine

import org.darkan.tools.engine.input.InputSystem
import org.darkan.tools.engine.render.RenderMetrics
import org.darkan.tools.engine.render.RenderSystem
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.scene.Scene
import org.darkan.tools.engine.window.Window
import org.darkan.tools.engine.window.WindowConfig
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.GL_BACK
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glReadBuffer
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

abstract class Engine(config: WindowConfig = WindowConfig()) {
    private companion object {
        /**
         * Largest single GPU-upload slice taken while the loading screen is up.
         * This bounds how long input can go unpolled, not how much work a "frame"
         * gets: the loop takes as many slices as fit before the next repaint.
         */
        const val LOADING_DRAIN_SLICE_NANOS = 25_000_000L

        /**
         * The loading screen repaints at `4x` its own cost, so it never claims more
         * than a quarter of the load, clamped to stay between ~7 and 20 Hz.
         */
        const val LOADING_RENDER_COST_MULTIPLE = 4
        const val LOADING_RENDER_MIN_INTERVAL_NANOS = 50_000_000L
        const val LOADING_RENDER_MAX_INTERVAL_NANOS = 150_000_000L

        /**
         * A loop iteration this short means the upload queue had nothing to give,
         * so the GL thread backs off rather than spinning against the loader pool.
         */
        const val LOADING_IDLE_ITERATION_NANOS = 1_000_000L
        const val LOADING_IDLE_SLEEP_MILLIS = 1L
    }

    val window = Window(config)
    val input = InputSystem(window)
    val renderSystem = RenderSystem(window)

    protected lateinit var colorShader: ShaderProgram
    protected lateinit var simpleShader: ShaderProgram
    protected lateinit var texturedShader: ShaderProgram

    var scene = Scene()
        protected set

    protected var isInitialLoading = false

    private var lastFrameTime: Long = 0
    var deltaTime: Float = 0f
        private set
    var fps: Int = 0
        private set

    private var frameCount = 0
    private var fpsTimer = 0f

    /** How long the last loading-screen repaint took, used to pace the next one. */
    private var loadingRenderCostNanos = 0L
    private var lastLoadingRenderNanos = 0L

    fun run() {
        try {
            init()
            loop()
        } finally {
            cleanup()
        }
    }

    private fun init() {
        window.init()
        input.init()

        val colorVertSrc = loadShaderSource("/shaders/color.vert")
        val colorFragSrc = loadShaderSource("/shaders/color.frag")
        colorShader = ShaderProgram.createWithUniforms(
            colorVertSrc, colorFragSrc,
            "model", "view", "projection",
            "ambientLight", "lightDirection", "lightColor"
        )

        val simpleVertSrc = loadShaderSource("/shaders/simple.vert")
        val simpleFragSrc = loadShaderSource("/shaders/simple.frag")
        simpleShader = ShaderProgram.createWithUniforms(
            simpleVertSrc, simpleFragSrc,
            "model", "view", "projection"
        )

        val texturedVertSrc = loadShaderSource("/shaders/textured.vert")
        val texturedFragSrc = loadShaderSource("/shaders/textured.frag")
        texturedShader = ShaderProgram.createWithUniforms(
            texturedVertSrc, texturedFragSrc,
            "model", "view", "projection",
            "ambientLight", "lightDirection", "lightColor",
            "textureSampler", "useTexture"
        )

        lastFrameTime = System.nanoTime()

        onInit()
    }

    private var loopFrameCount = 0

    // Offscreen capture hook. `-Ddarkan.capture=<file.png>` grabs the back buffer
    // once the scene has had `-Ddarkan.captureAfter=<seconds>` (default 12) to settle
    // and then closes the window. Reading the framebuffer directly beats screenshotting
    // the X window: the result is exactly what this process drew, with no dependence on
    // stacking order, focus or another process happening to sit on top of it.
    private val capturePath: String? = System.getProperty("darkan.capture")
    private val captureAfterSeconds: Float = System.getProperty("darkan.captureAfter", "12").toFloat()
    private var captureElapsed = 0f
    private var captureDone = false

    /**
     * Read the back buffer into a PNG at [path]. GL rows run bottom-up, so the
     * rows are flipped while copying into the [BufferedImage].
     */
    protected fun captureBackBuffer(path: String) {
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        val buffer = MemoryUtil.memAlloc(w * h * 4)
        try {
            glReadBuffer(GL_BACK)
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) {
                val srcRow = (h - 1 - y) * w * 4
                for (x in 0 until w) {
                    val i = srcRow + x * 4
                    val r = buffer.get(i).toInt() and 0xFF
                    val g = buffer.get(i + 1).toInt() and 0xFF
                    val b = buffer.get(i + 2).toInt() and 0xFF
                    image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            val file = File(path)
            file.parentFile?.mkdirs()
            ImageIO.write(image, "png", file)
            println("[ENGINE] Captured back buffer to $path (${w}x$h)")
            System.out.flush()
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun loop() {
        while (!window.shouldClose()) {
            val logFrame = loopFrameCount < 5

            val currentTime = System.nanoTime()
            deltaTime = (currentTime - lastFrameTime) / 1_000_000_000f
            lastFrameTime = currentTime

            fpsTimer += deltaTime
            if (fpsTimer >= 1f) {
                fps = frameCount
                frameCount = 0
                fpsTimer -= 1f
            }

            if (window.resized) {
                renderSystem.updateViewport()
                window.resized = false
            }

            // Drain GPU upload queue with adaptive budget.
            //
            // While the loading screen is up the queue is the only thing standing
            // between the user and a finished map, so it gets a whole slice rather
            // than a sliver: the overlay is repainted on its own timer below, and
            // every iteration in between is pure upload. Once the map is live the
            // budget goes back to a sliver, sized so a drain cannot cost a frame.
            val gpuBudgetNanos = if (isInitialLoading) {
                LOADING_DRAIN_SLICE_NANOS
            } else {
                // Adaptive: more budget when frames are fast, less when tight
                when {
                    deltaTime < 0.010f -> 6_000_000L   // <10ms frame: 6ms budget
                    deltaTime < 0.014f -> 4_000_000L   // 10-14ms: 4ms budget
                    else -> 2_000_000L                  // >14ms: 2ms budget
                }
            }
            onDrainGpuUploads(gpuBudgetNanos)

            handleInput()
            onUpdate(deltaTime)

            // Repainting the loading screen means redrawing the partially built
            // scene, which costs more than a whole upload slice. Do it on a timer
            // instead of every iteration; the frames in between are spent uploading.
            val renderNow = !isInitialLoading ||
                (currentTime - lastLoadingRenderNanos) >= loadingRenderIntervalNanos()

            if (renderNow) {
                frameCount++
                renderSystem.clear()
                if (isInitialLoading) {
                    val renderStart = System.nanoTime()
                    onRenderLoadingScreen()
                    loadingRenderCostNanos = System.nanoTime() - renderStart
                    lastLoadingRenderNanos = renderStart
                } else {
                    onRender()
                }
            }

            if (logFrame && renderNow) {
                println("[ENGINE] Frame $loopFrameCount: onRender() returned, calling window.update()")
                System.out.flush()
            }

            // Only ever grabbed straight after a render: skipped iterations leave
            // undefined contents in the back buffer.
            if (capturePath != null && !captureDone) {
                captureElapsed += deltaTime
                if (renderNow && captureElapsed >= captureAfterSeconds) {
                    onCapture()
                    captureBackBuffer(capturePath)
                    captureDone = true
                    window.requestClose()
                }
            }

            input.update()
            if (renderNow) {
                // Timed because with V-Sync the wait for the previous present does not
                // reliably land here: the driver is free to park it in whichever GL call
                // next needs the pipeline, which makes that call look expensive in the
                // render-thread timings. Reporting both halves is what makes the split
                // legible -- see RenderMetrics.gpuSyncWaitMs.
                val presentStart = System.nanoTime()
                window.update()
                RenderMetrics.presentTimeMs = (System.nanoTime() - presentStart) / 1_000_000.0
            } else {
                // No new image to present, but the window still has to stay alive.
                glfwPollEvents()
            }

            if (isInitialLoading && System.nanoTime() - currentTime < LOADING_IDLE_ITERATION_NANOS) {
                // Queue was empty - yield to the loader threads instead of spinning.
                Thread.sleep(LOADING_IDLE_SLEEP_MILLIS)
            }

            if (logFrame && renderNow) {
                println("[ENGINE] Frame $loopFrameCount: window.update() returned, frame complete")
                System.out.flush()
            }
            loopFrameCount++
        }
    }

    /**
     * How long to leave the loading screen on screen before repainting it, derived
     * from what the last repaint actually cost so the overlay's share of the load
     * stays bounded no matter how heavy the half-built scene is.
     */
    private fun loadingRenderIntervalNanos(): Long =
        (loadingRenderCostNanos * LOADING_RENDER_COST_MULTIPLE)
            .coerceIn(LOADING_RENDER_MIN_INTERVAL_NANOS, LOADING_RENDER_MAX_INTERVAL_NANOS)

    private fun handleInput() {
        if (input.keyboard.isKeyPressed(GLFW_KEY_ESCAPE)) {
            if (input.mouseCaptured) {
                input.releaseMouse()
            }
        }

        if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_RIGHT)) {
            input.captureMouse()
        }

        onInput()
    }

    protected open fun onInit() {}
    protected open fun onUpdate(deltaTime: Float) {}
    protected open fun onRender() {}
    protected open fun onRenderLoadingScreen() {}
    protected open fun onDrainGpuUploads(budgetNanos: Long) {}
    protected open fun onInput() {}
    protected open fun onCleanup() {}

    /**
     * Called once, on the frame the offscreen capture is taken, before the pixels are read.
     *
     * A capture run is headless by construction, so anything a subclass would otherwise show
     * in a debug panel (draw counts, batch counts) has no way out. Overriding this is how a
     * viewer gets those numbers onto stdout next to the image they describe.
     */
    protected open fun onCapture() {}

    private fun cleanup() {
        onCleanup()
        scene.cleanup()
        colorShader.cleanup()
        simpleShader.cleanup()
        texturedShader.cleanup()
        renderSystem.cleanup()
        window.cleanup()
    }

    protected fun loadShaderSource(resourcePath: String): String {
        return this::class.java.getResourceAsStream(resourcePath)?.bufferedReader()?.readText()
            ?: throw RuntimeException("Could not load shader: $resourcePath")
    }

    protected fun getAspectRatio(): Float {
        return if (window.height > 0) window.width.toFloat() / window.height.toFloat() else 1f
    }
}
