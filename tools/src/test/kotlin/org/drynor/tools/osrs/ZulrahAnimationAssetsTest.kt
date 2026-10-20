package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ZulrahAnimationAssetsTest {
    private val sequence = byteArrayOf(1, 0, 1, 0, 5, 0, 0, 0, 1, 0)
    private val sequenceAddress = Triple(Index.ANIMATIONS, 20001 ushr 7, 20001 and 127)
    private val frameAddress = Triple(Index.ANIMATION_FRAMES, 5001, 0)
    private val baseAddress = Triple(Index.ANIMATION_SKELETONS, 5001, 0)
    private fun source(frame: ByteArray = byteArrayOf(0, 1, 1, 1, 63), seq: ByteArray = sequence) = cache(mapOf(
        sequenceAddress to seq,
        frameAddress to frame,
        baseAddress to byteArrayOf(1, 1, 1, 7),
    ))

    @Test
    fun collisionSafeClosureReferencesAllocatedNativeFramesAndBases() {
        val original = byteArrayOf(0)
        val target = cache(mapOf(sequenceAddress to original, frameAddress to original, baseAddress to original))
        val plan = ZulrahAnimationAssets.plan(source(), target, setOf(20001))
        assertNotEquals(20001, plan.sequenceMap.getValue(20001))
        assertEquals(3, plan.assets.size)
        val seqAsset = plan.assets.single { it.index == Index.ANIMATIONS }
        val seq = AnimationDefinition(plan.sequenceMap.getValue(20001))
        AnimationDecoder().readLoop(seq, BufferReader(seqAsset.bytes))
        val frameAsset = plan.assets.single { it.index == Index.ANIMATION_FRAMES }
        assertEquals((frameAsset.archive shl 16) or frameAsset.file, seq.frames!!.single())
        val frame = AnimationFrameDefinition(seq.frames!!.single())
        AnimationFrameDecoder().readLoop(frame, BufferReader(frameAsset.bytes))
        assertContentEquals(intArrayOf(-4), frame.values)
        assertEquals(plan.assets.single { it.index == Index.ANIMATION_SKELETONS }.archive, frame.baseId)
        assertContentEquals(original, target.data(Index.ANIMATION_FRAMES, 5001, 0))
    }

    @Test
    fun nativeZeroBaseAndForeignEquipmentFailBeforeProducingPartialAssets() {
        assertFailsWith<IllegalArgumentException> {
            ZulrahAnimationAssets.plan(source(byteArrayOf(0, 0, 1, 1, 63)), cache(emptyMap()), setOf(20001))
        }
        assertFailsWith<IllegalArgumentException> {
            ZulrahAnimationAssets.plan(source(seq = byteArrayOf(6, 2, 1, 0)), cache(emptyMap()), setOf(20001))
        }
        assertFailsWith<IllegalArgumentException> {
            ZulrahAnimationAssets.plan(source(seq = byteArrayOf(13, 1, 0, 1, 0, 0)), cache(emptyMap()), setOf(20001))
        }
    }

    private fun cache(data: Map<Triple<Int, Int, Int>, ByteArray>): Cache = Proxy.newProxyInstance(
        Cache::class.java.classLoader, arrayOf(Cache::class.java),
    ) { _, method, args ->
        when (method.name) {
            "data" -> data[Triple(args[0] as Int, args[1] as Int, args[2] as Int)]
            "archiveIds" -> data.keys.filter { it.first == args[0] }.map { it.second }.distinct().sorted().toIntArray()
            "files" -> data.keys.filter { it.first == args[0] && it.second == args[1] }.map { it.third }.sorted().toIntArray()
            "close" -> null
            else -> error("Unexpected cache method ${method.name}")
        }
    } as Cache
}
