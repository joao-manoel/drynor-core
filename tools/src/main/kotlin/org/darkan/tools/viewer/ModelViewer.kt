package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import world.gregs.voidps.cache.definition.data.ModelDefinition
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.model.TexturedModel
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.scene.SceneNode
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.ui.AssetBrowser
import org.darkan.tools.ui.DebugPanel
import org.darkan.tools.ui.ImGuiLayer
import org.darkan.tools.ui.ImGuiText
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW.*
import world.gregs.voidps.cache.Cache
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive model viewer application.
 *
 * @param cache The game cache to load models from
 * @param initialModelId Optional model ID to load on startup. If null, tries default models.
 */
class ModelViewer(
    private val cache: Cache,
    private val initialModelId: Int? = null
) : Engine(WindowConfig(title = "Darkan Model Viewer", width = 1280, height = 720)) {

    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel
    private lateinit var assetBrowser: AssetBrowser

    private var modelNode: SceneNode? = null
    private var texturedModelNodes: MutableList<SceneNode> = mutableListOf()
    private var currentTexturedModel: TexturedModel? = null
    private var gridNode: SceneNode? = null
    private var currentModelId = -1

    private var autoRotate = false
    private var rotationSpeed = 30f

    // Face picker state
    private var selectedFaceIndex: Int = -1
    private var selectedFaceInfo: FaceInfo? = null
    private var showFacePicker = true

    /**
     * Detailed information about a selected face
     */
    data class FaceInfo(
        val faceIndex: Int,
        val vertexA: Int, val vertexB: Int, val vertexC: Int,
        val posA: Vector3f, val posB: Vector3f, val posC: Vector3f,
        val hslColor: Int,
        val rgbColor: Vector3f,
        val textureId: Int,    // -1 if no texture
        val alpha: Int,
        val renderType: Int,
        val priority: Int,
        val uvA: Pair<Float, Float>?,
        val uvB: Pair<Float, Float>?,
        val uvC: Pair<Float, Float>?
    )

    override fun onInit() {
        // Initialize texture loader
        TextureLoader.init(cache)

        imGui = ImGuiLayer(window)
        imGui.init()

        debugPanel = DebugPanel()
        assetBrowser = AssetBrowser(cache)
        assetBrowser.init()

        // Setup callbacks
        assetBrowser.setOnModelSelected { modelId ->
            loadModel(modelId)
        }

        assetBrowser.setOnItemSelected { id, def ->
            if (def.modelId > 0) {
                loadModel(def.modelId)
            }
        }

        assetBrowser.setOnNpcSelected { id, def ->
            def.modelIds?.firstOrNull()?.let { loadModel(it) }
        }

        assetBrowser.setOnObjectSelected { id, def ->
            def.modelIds?.firstOrNull()?.firstOrNull()?.let { loadModel(it) }
        }

        // Setup camera
        scene.camera.setPosition(0f, 2f, 5f)
        scene.camera.moveSpeed = 3f

        // Add grid
        gridNode = SceneNode(mesh = Mesh.createGrid(20f, 20))
        scene.addNode("grid", gridNode!!)

        // Load initial model - either from command line arg or try defaults
        if (initialModelId != null) {
            loadModel(initialModelId)
        } else {
            // Load default model - try several until we find a valid one
            for (id in listOf(54, 27, 0, 1, 10, 100, 1000, 2000, 5000)) {
                if (loadModel(id)) {
                    break
                }
            }
        }
    }

    fun loadModel(modelId: Int): Boolean {
        // Remove existing model nodes
        modelNode?.let {
            scene.removeNode("model")
            it.cleanup()
        }
        texturedModelNodes.forEachIndexed { index, node ->
            scene.removeNode("model_tex_$index")
            node.cleanup()
        }
        texturedModelNodes.clear()
        currentTexturedModel = null

        val texturedModel = ModelLoader.loadTexturedModel(cache, modelId)
        if (texturedModel != null) {
            var totalVertices = 0

            // Add color batch (untextured faces)
            texturedModel.colorBatch?.let { mesh ->
                if (mesh.vertexCount > 0) {
                    totalVertices += mesh.vertexCount
                    modelNode = SceneNode(mesh = mesh)
                    scene.addNode("model", modelNode!!)
                }
            }

            // Add textured batches
            texturedModel.textureBatches.forEachIndexed { index, batch ->
                if (batch.mesh.vertexCount > 0) {
                    totalVertices += batch.mesh.vertexCount
                    val node = SceneNode(mesh = batch.mesh)
                    scene.addNode("model_tex_$index", node)
                    texturedModelNodes.add(node)
                }
            }

            if (totalVertices > 0) {
                currentTexturedModel = texturedModel
                currentModelId = modelId
                return true
            }
        }

        modelNode = null
        currentModelId = -1
        return false
    }

    override fun onInput() {
        // Don't process game input if ImGui wants it
        if (imGui.wantCaptureKeyboard() || imGui.wantCaptureMouse()) {
            return
        }

        val camera = scene.camera

        // Camera movement
        if (input.keyboard.isKeyDown(GLFW_KEY_W)) camera.moveForward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_S)) camera.moveBackward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_A)) camera.moveLeft(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_D)) camera.moveRight(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_SPACE)) camera.moveUp(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_SHIFT)) camera.moveDown(deltaTime)

        // Camera rotation (when mouse captured)
        if (input.mouseCaptured) {
            camera.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
        }

        // Scroll zoom
        if (input.mouse.scrollY != 0.0) {
            camera.zoom(input.mouse.scrollY.toFloat())
        }

        // Keyboard shortcuts
        if (input.keyboard.isKeyPressed(GLFW_KEY_R)) {
            autoRotate = !autoRotate
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) {
            debugPanel.toggleVisibility()
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F2)) {
            assetBrowser.toggleVisibility()
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) {
            renderSystem.toggleWireframe()
        }

        // Model navigation
        if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT) || input.keyboard.isKeyPressed(GLFW_KEY_COMMA)) {
            if (currentModelId > 0) {
                loadModel(currentModelId - 1)
            }
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT) || input.keyboard.isKeyPressed(GLFW_KEY_PERIOD)) {
            val nextId = if (currentModelId < 0) 0 else currentModelId + 1
            loadModel(nextId)
        }

        // Toggle face picker
        if (input.keyboard.isKeyPressed(GLFW_KEY_F4)) {
            showFacePicker = !showFacePicker
        }

        // Face picking with left click (when not captured)
        if (!input.mouseCaptured && input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            pickFace(input.mouse.x.toFloat(), input.mouse.y.toFloat())
        }

        // Clear selection with middle click
        if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_MIDDLE)) {
            selectedFaceIndex = -1
            selectedFaceInfo = null
        }
    }

    /**
     * Pick a face at the given screen coordinates using ray-triangle intersection.
     */
    private fun pickFace(screenX: Float, screenY: Float) {
        val def = ModelLoader.getCachedDefinition(currentModelId) ?: return
        if (def.faceCount == 0) return

        // Get matrices
        val aspectRatio = getAspectRatio()
        val viewMatrix = scene.camera.getViewMatrix()
        val projectionMatrix = scene.camera.getProjectionMatrix(aspectRatio)

        // Get the model transform (from the first node, assume all share same transform)
        val modelMatrix = modelNode?.getModelMatrix()
            ?: texturedModelNodes.firstOrNull()?.getModelMatrix()
            ?: Matrix4f()

        // Calculate the ray from camera through mouse position
        val ray = screenToWorldRay(screenX, screenY, viewMatrix, projectionMatrix)
        val rayOrigin = ray.first
        val rayDir = ray.second

        // Find closest intersecting face
        var closestT = Float.MAX_VALUE
        var closestFace = -1

        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (aIdx < 0 || aIdx >= def.vertexCount ||
                bIdx < 0 || bIdx >= def.vertexCount ||
                cIdx < 0 || cIdx >= def.vertexCount) continue

            // Get vertices in OpenGL world space (apply model transform)
            val posA = transformVertex(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], modelMatrix)
            val posB = transformVertex(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], modelMatrix)
            val posC = transformVertex(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], modelMatrix)

            val t = rayTriangleIntersect(rayOrigin, rayDir, posA, posB, posC)
            if (t != null && t > 0 && t < closestT) {
                closestT = t
                closestFace = i
            }
        }

        if (closestFace >= 0) {
            selectedFaceIndex = closestFace
            selectedFaceInfo = buildFaceInfo(def, closestFace)
        }
    }

    /**
     * Convert RS coordinates to OpenGL and apply model transformation.
     */
    private fun transformVertex(rsX: Int, rsY: Int, rsZ: Int, modelMatrix: Matrix4f): Vector3f {
        val glPos = CoordinateUtil.rsToOpenGL(rsX, rsY, rsZ)
        val transformed = Vector4f(glPos.x, glPos.y, glPos.z, 1f)
        modelMatrix.transform(transformed)
        return Vector3f(transformed.x, transformed.y, transformed.z)
    }

    /**
     * Convert screen coordinates to a world-space ray.
     */
    private fun screenToWorldRay(
        screenX: Float, screenY: Float,
        viewMatrix: Matrix4f, projectionMatrix: Matrix4f
    ): Pair<Vector3f, Vector3f> {
        // Convert to normalized device coordinates (-1 to 1)
        val ndcX = (2f * screenX / window.width) - 1f
        val ndcY = 1f - (2f * screenY / window.height) // Flip Y

        // Create inverse matrices
        val invProj = Matrix4f(projectionMatrix).invert()
        val invView = Matrix4f(viewMatrix).invert()

        // Near point in NDC (z = -1)
        val nearNdc = Vector4f(ndcX, ndcY, -1f, 1f)
        invProj.transform(nearNdc)
        nearNdc.div(nearNdc.w)
        invView.transform(nearNdc)

        // Far point in NDC (z = 1)
        val farNdc = Vector4f(ndcX, ndcY, 1f, 1f)
        invProj.transform(farNdc)
        farNdc.div(farNdc.w)
        invView.transform(farNdc)

        val origin = Vector3f(nearNdc.x, nearNdc.y, nearNdc.z)
        val direction = Vector3f(farNdc.x - nearNdc.x, farNdc.y - nearNdc.y, farNdc.z - nearNdc.z).normalize()

        return Pair(origin, direction)
    }

    /**
     * Moller-Trumbore ray-triangle intersection algorithm.
     * Returns the distance t along the ray if hit, null otherwise.
     */
    private fun rayTriangleIntersect(
        rayOrigin: Vector3f, rayDir: Vector3f,
        v0: Vector3f, v1: Vector3f, v2: Vector3f
    ): Float? {
        val epsilon = 0.0000001f

        val edge1 = Vector3f(v1).sub(v0)
        val edge2 = Vector3f(v2).sub(v0)

        val h = Vector3f(rayDir).cross(edge2)
        val a = edge1.dot(h)

        if (a > -epsilon && a < epsilon) return null // Ray parallel to triangle

        val f = 1f / a
        val s = Vector3f(rayOrigin).sub(v0)
        val u = f * s.dot(h)

        if (u < 0f || u > 1f) return null

        val q = Vector3f(s).cross(edge1)
        val v = f * rayDir.dot(q)

        if (v < 0f || u + v > 1f) return null

        val t = f * edge2.dot(q)
        return if (t > epsilon) t else null
    }

    /**
     * Build detailed face information for display.
     */
    private fun buildFaceInfo(def: ModelDefinition, faceIndex: Int): FaceInfo {
        val aIdx = def.faceA[faceIndex]
        val bIdx = def.faceB[faceIndex]
        val cIdx = def.faceC[faceIndex]

        val posA = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
        val posB = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
        val posC = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

        val hsl = def.faceColors[faceIndex].toInt() and 0xFFFF
        val rgb = ColorUtil.hslToRgb(hsl)

        val textureIdSigned = def.faceTextures?.get(faceIndex)?.toInt() ?: -1
        val textureId = if (textureIdSigned == -1) -1 else (textureIdSigned and 0xFFFF)

        val alpha = def.faceAlphas?.get(faceIndex)?.toInt()?.and(0xFF) ?: 255
        val renderType = def.faceRenderTypes?.get(faceIndex)?.toInt()?.and(0xFF) ?: 0
        val priority = def.facePriorities?.get(faceIndex)?.toInt()?.and(0xFF) ?: 0

        val uvA = if (def.textureCoordU != null && aIdx < def.textureCoordU!!.size)
            Pair(def.textureCoordU!![aIdx], def.textureCoordV!![aIdx]) else null
        val uvB = if (def.textureCoordU != null && bIdx < def.textureCoordU!!.size)
            Pair(def.textureCoordU!![bIdx], def.textureCoordV!![bIdx]) else null
        val uvC = if (def.textureCoordU != null && cIdx < def.textureCoordU!!.size)
            Pair(def.textureCoordU!![cIdx], def.textureCoordV!![cIdx]) else null

        return FaceInfo(
            faceIndex = faceIndex,
            vertexA = aIdx, vertexB = bIdx, vertexC = cIdx,
            posA = posA, posB = posB, posC = posC,
            hslColor = hsl,
            rgbColor = rgb,
            textureId = textureId,
            alpha = alpha,
            renderType = renderType,
            priority = priority,
            uvA = uvA, uvB = uvB, uvC = uvC
        )
    }

    override fun onUpdate(deltaTime: Float) {
        // Auto-rotate model
        if (autoRotate) {
            val angle = rotationSpeed * deltaTime
            val radians = Math.toRadians(angle.toDouble()).toFloat()

            modelNode?.rotation?.rotateY(radians)
            texturedModelNodes.forEach { it.rotation.rotateY(radians) }
        }
    }

    override fun onRender() {
        val aspectRatio = getAspectRatio()
        val viewMatrix = scene.camera.getViewMatrix()
        val projectionMatrix = scene.camera.getProjectionMatrix(aspectRatio)

        // Render untextured elements (grid and color batch) with color shader
        colorShader.bind()
        colorShader.setUniform("view", viewMatrix)
        colorShader.setUniform("projection", projectionMatrix)
        colorShader.setUniform("ambientLight", scene.ambientLight)
        colorShader.setUniform("lightDirection", scene.lightDirection)
        colorShader.setUniform("lightColor", scene.lightColor)

        // Render grid
        gridNode?.let { node ->
            colorShader.setUniform("model", node.getModelMatrix())
            node.mesh?.render()
        }

        // Render untextured faces. RS model geometry is single-sided (see
        // RenderSystem.enableModelCullFace); the grid above is not, so cull only here.
        modelNode?.let { node ->
            renderSystem.enableModelCullFace()
            colorShader.setUniform("model", node.getModelMatrix())
            node.mesh?.render()
            renderSystem.disableCullFace()
            renderSystem.resetFrontFace()
        }

        colorShader.unbind()

        // Render textured batches using two-pass approach matching the RS client:
        // Pass 1: Opaque materials (no blending, depth write ON)
        // Pass 2: Transparent materials (alpha blending, depth write OFF but depth test ON)
        if (texturedModelNodes.isNotEmpty() && currentTexturedModel != null) {
            // RS models are single-sided; the client back-face culls them.
            renderSystem.enableModelCullFace()

            texturedShader.bind()
            texturedShader.setUniform("view", viewMatrix)
            texturedShader.setUniform("projection", projectionMatrix)
            texturedShader.setUniform("ambientLight", scene.ambientLight)
            texturedShader.setUniform("lightDirection", scene.lightDirection)
            texturedShader.setUniform("lightColor", scene.lightColor)
            texturedShader.setUniform("textureSampler", 0)

            // --- Pass 1: Opaque batches ---
            // No blending, depth write enabled (default state)
            renderSystem.disableBlending()
            renderSystem.enableDepthWrite()

            currentTexturedModel!!.textureBatches.forEachIndexed { index, batch ->
                if (!TextureLoader.isOpaque(batch.textureId)) return@forEachIndexed
                val node = texturedModelNodes.getOrNull(index) ?: return@forEachIndexed

                val textureId = TextureLoader.getTextureForMaterial(cache, batch.textureId)
                TextureLoader.bindTexture(textureId, 0)
                texturedShader.setUniform("useTexture", true)
                texturedShader.setUniform("model", node.getModelMatrix())
                node.mesh?.render()
            }

            // --- Pass 2: Transparent batches ---
            // Enable alpha blending, disable depth write (but depth test remains on)
            renderSystem.enableBlending()
            renderSystem.disableDepthWrite()

            currentTexturedModel!!.textureBatches.forEachIndexed { index, batch ->
                if (TextureLoader.isOpaque(batch.textureId)) return@forEachIndexed
                val node = texturedModelNodes.getOrNull(index) ?: return@forEachIndexed

                val textureId = TextureLoader.getTextureForMaterial(cache, batch.textureId)
                TextureLoader.bindTexture(textureId, 0)
                texturedShader.setUniform("useTexture", true)
                texturedShader.setUniform("model", node.getModelMatrix())
                node.mesh?.render()
            }

            // Restore default state
            renderSystem.enableDepthWrite()
            renderSystem.disableBlending()
            TextureLoader.unbindTexture()
            texturedShader.unbind()
            renderSystem.resetFrontFace()
            renderSystem.enableCullFace()
        }

        // Render ImGui
        imGui.beginFrame()

        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.modelCount = if (currentModelId >= 0) 1 else 0
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera, window.width, window.height) {
            renderSystem.toggleWireframe()
        }

        assetBrowser.render()

        // Model info panel
        renderModelInfo()

        // Face picker panel
        if (showFacePicker) {
            renderFacePickerPanel()
        }

        imGui.endFrame()
    }

    // Track first frame for initial positioning
    private var facePickerFirstFrame = true

    /**
     * Render the face picker information panel.
     */
    private fun renderFacePickerPanel() {
        // Set size constraints: min width 340, max height 80% of screen
        val maxHeight = window.height * 0.8f
        ImGui.setNextWindowSizeConstraints(340f, 150f, 450f, maxHeight)

        // Only set initial position on first frame
        if (facePickerFirstFrame) {
            ImGui.setNextWindowPos(ImGui.getIO().displaySizeX - 350f, 10f)
            facePickerFirstFrame = false
        }

        // Allow moving and resizing, scrollbar appears automatically when needed
        val flags = ImGuiWindowFlags.None
        if (ImGui.begin("Face Picker (F4)", flags)) {
            // Clamp window position to stay on screen
            clampWindowToScreen()
            ImGui.text("Left-click to select face")
            ImGui.text("Middle-click to clear selection")
            ImGui.separator()

            val info = selectedFaceInfo
            if (info != null) {
                ImGuiText.coloured(0f, 1f, 0f, 1f, "Face #${info.faceIndex}")
                ImGui.separator()

                // Vertex indices
                ImGui.text("Vertex Indices:")
                ImGuiText.bullet("A: ${info.vertexA}")
                ImGuiText.bullet("B: ${info.vertexB}")
                ImGuiText.bullet("C: ${info.vertexC}")

                ImGui.separator()

                // Positions (OpenGL space)
                ImGui.text("Positions (GL):")
                ImGuiText.bullet("A: (%.2f, %.2f, %.2f)".format(info.posA.x, info.posA.y, info.posA.z))
                ImGuiText.bullet("B: (%.2f, %.2f, %.2f)".format(info.posB.x, info.posB.y, info.posB.z))
                ImGuiText.bullet("C: (%.2f, %.2f, %.2f)".format(info.posC.x, info.posC.y, info.posC.z))

                ImGui.separator()

                // Color info
                ImGui.text("Color:")
                ImGuiText.bullet("HSL Raw: ${info.hslColor} (0x${info.hslColor.toString(16).uppercase()})")

                // Decode HSL components
                val hue = (info.hslColor shr 10) and 0x3f
                val sat = (info.hslColor shr 7) and 0x7
                val light = info.hslColor and 0x7f
                ImGuiText.bullet("H: $hue  S: $sat  L: $light")

                ImGuiText.bullet("RGB: (%.2f, %.2f, %.2f)".format(info.rgbColor.x, info.rgbColor.y, info.rgbColor.z))

                // Show color swatch
                val rgb255 = intArrayOf(
                    (info.rgbColor.x * 255).toInt(),
                    (info.rgbColor.y * 255).toInt(),
                    (info.rgbColor.z * 255).toInt()
                )
                ImGuiText.bullet("RGB255: (${rgb255[0]}, ${rgb255[1]}, ${rgb255[2]})")

                ImGui.separator()

                // Texture info
                ImGui.text("Texture:")
                if (info.textureId >= 0) {
                    ImGuiText.coloured(1f, 1f, 0f, 1f, "Texture ID: ${info.textureId}")
                } else {
                    ImGui.text("No texture (untextured face)")
                }

                ImGui.separator()

                // Other face properties
                ImGui.text("Properties:")
                ImGuiText.bullet("Alpha: ${info.alpha}")
                ImGuiText.bullet("Render Type: ${info.renderType}")
                ImGuiText.bullet("Priority: ${info.priority}")

                // UV coordinates
                if (info.uvA != null || info.uvB != null || info.uvC != null) {
                    ImGui.separator()
                    ImGui.text("UV Coordinates:")
                    info.uvA?.let { ImGuiText.bullet("A: (%.4f, %.4f)".format(it.first, it.second)) }
                    info.uvB?.let { ImGuiText.bullet("B: (%.4f, %.4f)".format(it.first, it.second)) }
                    info.uvC?.let { ImGuiText.bullet("C: (%.4f, %.4f)".format(it.first, it.second)) }
                }

                ImGui.separator()

                // Copy button for easy sharing
                if (ImGui.button("Copy Face Info")) {
                    // Face info is displayed in the panel - clipboard copy would require GLFW integration
                }
            } else {
                ImGuiText.coloured(0.5f, 0.5f, 0.5f, 1f, "No face selected")
                ImGui.text("Click on the model to select a face")
            }
        }
        ImGui.end()
    }

    private fun renderModelInfo() {
        if (currentModelId < 0) return

        ImGui.setNextWindowPos(10f, ImGui.getIO().displaySizeY - 200f)
        ImGui.setNextWindowSize(280f, 0f)

        if (ImGui.begin("Model Info", ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize)) {
            ImGui.text("Model ID: $currentModelId")

            val def = ModelLoader.getCachedDefinition(currentModelId)
            if (def != null) {
                ImGui.text("Vertices: ${def.vertexCount}")
                ImGui.text("Faces: ${def.faceCount}")
            }

            // Texture info
            currentTexturedModel?.let { model ->
                ImGui.separator()
                ImGui.text("Textures: ${if (model.hasTextures) "Yes" else "No"}")
                if (model.textureBatches.isNotEmpty()) {
                    ImGui.text("Texture batches: ${model.textureBatches.size}")
                    model.textureBatches.forEach { batch ->
                        ImGuiText.bullet("Material ${batch.textureId}: ${batch.mesh.vertexCount} verts")
                    }
                }
            }

            // Raw face texture data from model definition
            if (def != null && def.faceTextures != null) {
                ImGui.separator()
                ImGui.text("Raw faceTextures (first 20):")
                val uniqueTextures = mutableSetOf<Int>()
                for (i in 0 until minOf(def.faceCount, def.faceTextures!!.size)) {
                    val rawTex = def.faceTextures!![i].toInt() and 0xFFFF
                    if (rawTex > 0 && rawTex != 65535) {
                        uniqueTextures.add(rawTex)
                    }
                }
                ImGui.text("Unique texture IDs: ${uniqueTextures.sorted()}")
            }

            ImGui.separator()
            if (ImGui.checkbox("Auto-Rotate (R)", autoRotate)) {
                autoRotate = !autoRotate
            }

            ImGui.separator()
            ImGui.text("Navigation: < > or , .")
            ImGui.text("Face Picker: F4")
        }
        ImGui.end()
    }

    /**
     * Clamp the current ImGui window position to stay within screen bounds.
     * Call this inside ImGui.begin() block.
     */
    private fun clampWindowToScreen() {
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()
        val screenWidth = window.width.toFloat()
        val screenHeight = window.height.toFloat()

        var newX = pos.x
        var newY = pos.y

        // Clamp X: keep at least 50px visible on screen
        if (newX + size.x < 50f) newX = 50f - size.x
        if (newX > screenWidth - 50f) newX = screenWidth - 50f

        // Clamp Y: keep title bar on screen (at least 20px from top)
        if (newY < 0f) newY = 0f
        if (newY > screenHeight - 50f) newY = screenHeight - 50f

        if (newX != pos.x || newY != pos.y) {
            ImGui.setWindowPos(newX, newY)
        }
    }

    override fun onCleanup() {
        ModelLoader.clearCache()
        TextureLoader.clearCache()
        imGui.cleanup()
    }
}
