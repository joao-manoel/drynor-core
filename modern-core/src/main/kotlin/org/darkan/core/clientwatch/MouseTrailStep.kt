package org.darkan.core.clientwatch

data class MouseTrailStep(
    val type: Type,
    val frames: Int,
    val x: Int,
    val y: Int,
    val hardware: Boolean = true
) {
    enum class Type {
        SET_POSITION, MOVE_OFFSET
    }
}