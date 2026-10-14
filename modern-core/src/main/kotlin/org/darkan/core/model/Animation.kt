package org.darkan.core.model

data class Animation(val ids: IntArray, val speed: Int = 0) {

    @JvmOverloads
    constructor(id: Int, speed: Int = 0) : this(intArrayOf(id, id, id, id), speed)

    @JvmOverloads
    constructor(id1: Int, id2: Int, id3: Int, id4: Int, speed: Int = 0) : this(intArrayOf(id1, id2, id3, id4), speed)

    val primaryId get() = ids[0]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Animation) return false
        return ids.contentEquals(other.ids) && speed == other.speed
    }

    override fun hashCode() = 31 * ids.contentHashCode() + speed

    override fun toString() = "Animation(ids=${ids.contentToString()}, speed=$speed)"

    companion object {
        val NONE = Animation(-1)
    }
}
