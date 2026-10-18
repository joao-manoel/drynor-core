package org.drynor.core.model

enum class SoundType {
    EFFECT,
    VORB_EFFECT,
    VOICE,
    JINGLE,
    MUSIC
}

data class Sound(
    var id: Int,
    var delay: Int = 0,
    var volume: Int = 255,
    var sampleRate: Int = 256,
    var type: SoundType = SoundType.EFFECT,
    var repeat: Int = 1,
    var radius: Int = 1
) {
    constructor(id: Int, delay: Int, type: SoundType) : this(id, delay, 255, 256, type)
    constructor(id: Int, type: SoundType) : this(id, 0, 255, 256, type)

    fun volume(volume: Int): Sound { this.volume = volume; return this }
    fun delay(delay: Int): Sound { this.delay = delay; return this }
    fun sampleRate(sampleRate: Int): Sound { this.sampleRate = sampleRate; return this }
    fun repeat(repeat: Int): Sound { this.repeat = repeat; return this }
    fun radius(radius: Int): Sound { this.radius = radius; return this }
}
