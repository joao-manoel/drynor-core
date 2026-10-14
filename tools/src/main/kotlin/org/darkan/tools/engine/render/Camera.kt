package org.darkan.tools.engine.render

import org.darkan.tools.engine.spatial.Frustum
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

class Camera(
    val position: Vector3f = Vector3f(0f, 0f, 3f),
    var yaw: Float = -90f,
    var pitch: Float = 0f
) {
    val front: Vector3f = Vector3f(0f, 0f, -1f)
    val up: Vector3f = Vector3f(0f, 1f, 0f)
    val right: Vector3f = Vector3f(1f, 0f, 0f)

    private val worldUp = Vector3f(0f, 1f, 0f)

    var fov: Float = 45f
    var nearPlane: Float = 1.0f
    var farPlane: Float = 1000f
    var moveSpeed: Float = 5.0f
    var mouseSensitivity: Float = 0.1f

    var orthographic: Boolean = false
    var orthoSize: Float = 10f

    // Cached matrices to reduce allocations
    private val cachedViewMatrix = Matrix4f()
    private val cachedProjectionMatrix = Matrix4f()
    private val cachedViewProjectionMatrix = Matrix4f()
    private val tempTarget = Vector3f()

    // Frustum for culling
    private val frustum = Frustum()
    private var frustumDirty = true
    private var lastAspectRatio = 0f

    init {
        updateVectors()
    }

    fun getViewMatrix(): Matrix4f {
        tempTarget.set(position).add(front)
        cachedViewMatrix.identity().lookAt(position, tempTarget, up)
        frustumDirty = true
        return cachedViewMatrix
    }

    /**
     * Get a cached copy of the view matrix (avoids allocation).
     * Use this when you don't need to modify the matrix.
     */
    fun getViewMatrixCached(): Matrix4f {
        tempTarget.set(position).add(front)
        return cachedViewMatrix.identity().lookAt(position, tempTarget, up)
    }

    fun getProjectionMatrix(aspectRatio: Float): Matrix4f {
        if (aspectRatio != lastAspectRatio) {
            frustumDirty = true
            lastAspectRatio = aspectRatio
        }

        return if (orthographic) {
            val halfHeight = orthoSize / 2
            val halfWidth = halfHeight * aspectRatio
            cachedProjectionMatrix.identity().ortho(-halfWidth, halfWidth, -halfHeight, halfHeight, nearPlane, farPlane)
        } else {
            cachedProjectionMatrix.identity().perspective(Math.toRadians(fov.toDouble()).toFloat(), aspectRatio, nearPlane, farPlane)
        }
    }

    /**
     * Get the cached frustum for culling.
     * Automatically updates if the camera has moved.
     */
    fun getFrustum(aspectRatio: Float): Frustum {
        if (frustumDirty) {
            updateFrustum(aspectRatio)
        }
        return frustum
    }

    /**
     * Force update the frustum.
     */
    fun updateFrustum(aspectRatio: Float) {
        getViewMatrixCached()
        getProjectionMatrix(aspectRatio)
        cachedProjectionMatrix.mul(cachedViewMatrix, cachedViewProjectionMatrix)
        frustum.update(cachedViewProjectionMatrix)
        frustumDirty = false
    }

    /**
     * Mark frustum as needing update (call after any camera parameter changes).
     */
    fun invalidateFrustum() {
        frustumDirty = true
    }

    /**
     * Get the combined view-projection matrix.
     */
    fun getViewProjectionMatrix(aspectRatio: Float): Matrix4f {
        getViewMatrixCached()
        getProjectionMatrix(aspectRatio)
        return cachedProjectionMatrix.mul(cachedViewMatrix, cachedViewProjectionMatrix)
    }

    fun moveForward(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(front).mul(velocity)
        position.add(movement)
        frustumDirty = true
    }

    fun moveBackward(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(front).mul(velocity)
        position.sub(movement)
        frustumDirty = true
    }

    fun moveLeft(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(right).mul(velocity)
        position.sub(movement)
        frustumDirty = true
    }

    fun moveRight(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(right).mul(velocity)
        position.add(movement)
        frustumDirty = true
    }

    fun moveUp(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        position.y += velocity
        frustumDirty = true
    }

    fun moveDown(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        position.y -= velocity
        frustumDirty = true
    }

    fun rotate(xOffset: Float, yOffset: Float, constrainPitch: Boolean = true) {
        yaw += xOffset * mouseSensitivity
        pitch -= yOffset * mouseSensitivity

        if (constrainPitch) {
            pitch = pitch.coerceIn(-89f, 89f)
        }

        updateVectors()
        frustumDirty = true
    }

    fun zoom(yOffset: Float) {
        fov -= yOffset
        fov = fov.coerceIn(1f, 120f)
        frustumDirty = true
    }

    private fun updateVectors() {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())

        front.x = (cos(yawRad) * cos(pitchRad)).toFloat()
        front.y = sin(pitchRad).toFloat()
        front.z = (sin(yawRad) * cos(pitchRad)).toFloat()
        front.normalize()

        front.cross(worldUp, right)
        right.normalize()

        right.cross(front, up)
        up.normalize()
    }

    fun lookAt(target: Vector3f) {
        val direction = Vector3f()
        target.sub(position, direction)
        direction.normalize()

        pitch = Math.toDegrees(Math.asin(direction.y.toDouble())).toFloat()
        yaw = Math.toDegrees(Math.atan2(direction.z.toDouble(), direction.x.toDouble())).toFloat()

        updateVectors()
        frustumDirty = true
    }

    fun setPosition(x: Float, y: Float, z: Float) {
        position.set(x, y, z)
        frustumDirty = true
    }
}
