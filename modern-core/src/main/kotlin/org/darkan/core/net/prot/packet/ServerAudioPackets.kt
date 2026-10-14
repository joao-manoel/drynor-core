/**
 * Sound effect, vorbis stream and music packets.
 */
package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt

data class VorbisSound(val soundId: Int, val repeat: Int, val delay: Int, val volume: Int, val sampleRate: Int) : ServerProt

data class VorbisSpeechSound(val soundId: Int, val repeat: Int, val delay: Int, val volume: Int) : ServerProt

data class SoundSynth(val soundId: Int, val repeat: Int, val delay: Int, val volume: Int, val sampleRate: Int) : ServerProt

data class MusicEffect(val unused: Int, val volume: Int, val musicId: Int) : ServerProt

data class MusicTrack(val delay: Int, val volume: Int, val musicId: Int) : ServerProt

@JvmInline value class PreloadSong(val songId: Int) : ServerProt

/**
 * Opcode 11, fixed size 2. Client enum `STOP_VORBIS_STREAM`.
 *
 * Stops the currently streaming vorbis file [soundId] (index 40 file id).
 */
@JvmInline value class StopVorbisStream(val soundId: Int) : ServerProt

/**
 * Opcode 17, fixed size 3. Client enum `VORBIS_PRELOAD_SOUND`.
 *
 * Preloads the vorbis file [soundId] at the given [priority], looping enabled.
 */
data class VorbisPreloadSound(val soundId: Int, val priority: Int) : ServerProt

/**
 * Opcode 106, fixed size 2. Client enum `SET_VORBIS_SOUND`.
 *
 * Selects the vorbis file [soundId] as the active vorbis sound.
 */
@JvmInline value class SetVorbisSound(val soundId: Int) : ServerProt

data class SoundMixbussSetLevel(val index: Int, val level: Int) : ServerProt

@JvmInline value class ResetSounds(val dummy: Int = 0) : ServerProt
