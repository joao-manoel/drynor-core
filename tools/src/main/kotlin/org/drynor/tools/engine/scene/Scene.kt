package org.drynor.tools.engine.scene

import org.drynor.tools.engine.render.Camera
import org.drynor.tools.engine.render.ShaderProgram
import org.joml.Matrix4f
import org.joml.Vector3f

class Scene {
    val root = SceneNode()
    var camera = Camera()

    var ambientLight = Vector3f(0.3f, 0.3f, 0.3f)
    var lightDirection = Vector3f(-0.5f, -1f, -0.5f).normalize()
    var lightColor = Vector3f(1f, 1f, 1f)

    private val nodes = mutableMapOf<String, SceneNode>()

    fun addNode(name: String, node: SceneNode) {
        nodes[name] = node
        root.addChild(node)
    }

    fun getNode(name: String): SceneNode? = nodes[name]

    fun removeNode(name: String): SceneNode? {
        val node = nodes.remove(name)
        node?.let { root.removeChild(it) }
        return node
    }

    fun render(shader: ShaderProgram, aspectRatio: Float) {
        shader.bind()

        val viewMatrix = camera.getViewMatrix()
        val projectionMatrix = camera.getProjectionMatrix(aspectRatio)

        shader.setUniform("view", viewMatrix)
        shader.setUniform("projection", projectionMatrix)
        shader.setUniform("ambientLight", ambientLight)
        shader.setUniform("lightDirection", lightDirection)
        shader.setUniform("lightColor", lightColor)

        root.render { node, modelMatrix ->
            shader.setUniform("model", modelMatrix)
            node.mesh?.render()
        }

        shader.unbind()
    }

    fun renderWireframe(shader: ShaderProgram, aspectRatio: Float) {
        shader.bind()

        val viewMatrix = camera.getViewMatrix()
        val projectionMatrix = camera.getProjectionMatrix(aspectRatio)

        shader.setUniform("view", viewMatrix)
        shader.setUniform("projection", projectionMatrix)

        root.render { node, modelMatrix ->
            shader.setUniform("model", modelMatrix)
            node.mesh?.renderWireframe()
        }

        shader.unbind()
    }

    fun cleanup() {
        root.cleanup()
        nodes.clear()
    }
}
