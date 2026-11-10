package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder

class PoolAnimationAssetsTest {
    @Test fun `OSRS loop and radius bits translate to audible native sequence sound`() {
        val native = PoolAnimationAssets.nativePoolSound((2401 shl 8) or 17)
        assertEquals(2401, native ushr 8)
        assertEquals(1, (native ushr 5) and 7)
        assertEquals(1, native and 31)
        val local = PoolAnimationAssets.nativePoolSound((2401 shl 8) or 16)
        assertEquals(1, (local ushr 5) and 7)
        assertEquals(0, local and 31)
    }
    @Test fun `pool zero override is audited without granting arbitrary foreign item overrides`() {
        assertTrue(ZulrahAnimationAssets.auditedEquipmentOverride(27305, setOf(0)))
        assertFalse(ZulrahAnimationAssets.auditedEquipmentOverride(27305, setOf(819)))
        assertFalse(ZulrahAnimationAssets.auditedEquipmentOverride(27305, setOf(0, 819)))
        assertFalse(ZulrahAnimationAssets.auditedEquipmentOverride(27306, setOf(0)))
        assertTrue(ZulrahAnimationAssets.auditedEquipmentOverride(20623, setOf(819)))
    }

    @Test fun `pool hand hiding and verified sip sound survive native encode decode`() {
        val packedSound = (2401 shl 8) or 16
        val raw = byteArrayOf(6, 0, 0, 7, 0, 0, 13, 1, (packedSound ushr 16).toByte(), (packedSound ushr 8).toByte(), packedSound.toByte(), 0)
        val converted = OsrsAnimationConverter.convertSequence(30000, raw, emptyMap(),
            verifiedSoundIds = setOf(2401), preserveSceneEquipmentOverrides = true)
        val writer = BufferWriter(128)
        with(AnimationEncoder()) { writer.encode(converted) }
        val read = AnimationDefinition(30000)
        AnimationDecoder().readLoop(read, BufferReader(writer.toArray()))
        assertEquals(65535, read.leftHandItem)
        assertEquals(65535, read.rightHandItem)
        assertContentEquals(intArrayOf(packedSound), read.sounds!![0])
    }
}
