package org.darkan.core.model

data class SpotAnim(
    val id: Int,
    val speed: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0
) {
    constructor(id: Int) : this(id, 0, 0, 0)
    constructor(id: Int, speed: Int) : this(id, speed, 0, 0)
    constructor(id: Int, speed: Int, height: Int) : this(id, speed, height, 0)

    val settingsHash: Int get() = (speed and 0xffff) or (height shl 16)
    val settings2Hash: Int get() = rotation and 0x7
}
