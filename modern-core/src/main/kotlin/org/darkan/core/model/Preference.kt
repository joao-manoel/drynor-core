package org.darkan.core.model

enum class Preference(private val index: Int) {
    ANTI_ALIASING(1),
    BLOOM(2),
    BRIGHTNESS(3),
    UNK4(4),
    UNK5(5),
    UNK6(6),
    FOG(7),
    UNK8(8),
    GROUND_DECORATION(9),
    IDLE_ANIMATIONS(10),
    LIGHT_DETAIL(11),
    SCENERY_SHADOWS(12),
    UNK13(13),
    PARTICLES(14),
    UNK15(15),
    UNK16(16),
    UNK17(17),
    UNK18(18),
    TEXTURES(19),
    UNK20(20),
    WATER(22),
    SCREEN_SIZE(23),
    CUSTOM_CURSORS(24),
    GRAPHICS(25),
    CPU(26),
    UNK27(27),
    SAFE_MODE(28),
    UNK29(29),
    SOUND_EFFECT_VOL(30),
    AMBIENT_SOUND_VOL(31),
    VOICE_VOL(32),
    MUSIC_VOL(33),
    UNK34(34),
    MONO_STEREO(35);

    companion object {
        private val MAP = entries.associateBy { it.index }
        fun forIndex(index: Int) = MAP[index]
    }
}