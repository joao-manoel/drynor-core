package org.drynor.tools.engine.scene

import org.drynor.tools.engine.render.Mesh
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

class SceneNode(
    var mesh: Mesh? = null,
    val position: Vector3f = Vector3f(0f, 0f, 0f),
    val rotation: Quaternionf = Quaternionf(),
    val scale: Vector3f = Vector3f(1f, 1f, 1f)
) {
    private val children = mutableListOf<SceneNode>()
    var parent: SceneNode? = null
        private set

    var visible: Boolean = true

    fun addChild(child: SceneNode) {
        child.parent?.removeChild(child)
        children.add(child)
        child.parent = this
    }

    fun removeChild(child: SceneNode) {
        if (children.remove(child)) {
            child.parent = null
        }
    }

    fun getChildren(): List<SceneNode> = children.toList()

    fun getModelMatrix(): Matrix4f {
        val modelMatrix = Matrix4f()
            .translate(position)
            .rotate(rotation)
            .scale(scale)

        return parent?.let { parentNode ->
            Matrix4f(parentNode.getModelMatrix()).mul(modelMatrix)
        } ?: modelMatrix
    }

    fun setRotationEuler(pitch: Float, yaw: Float, roll: Float) {
        rotation.identity()
            .rotateY(Math.toRadians(yaw.toDouble()).toFloat())
            .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
            .rotateZ(Math.toRadians(roll.toDouble()).toFloat())
    }

    fun translate(dx: Float, dy: Float, dz: Float) {
        position.add(dx, dy, dz)
    }

    fun setPosition(x: Float, y: Float, z: Float) {
        position.set(x, y, z)
    }

    fun setScale(uniformScale: Float) {
        scale.set(uniformScale, uniformScale, uniformScale)
    }

    fun setScale(x: Float, y: Float, z: Float) {
        scale.set(x, y, z)
    }

    fun render(renderCallback: (SceneNode, Matrix4f) -> Unit) {
        if (!visible) return

        mesh?.let {
            renderCallback(this, getModelMatrix())
        }

        children.forEach { it.render(renderCallback) }
    }

    fun cleanup() {
        mesh?.cleanup()
        children.forEach { it.cleanup() }
    }
}
