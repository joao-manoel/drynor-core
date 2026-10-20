package org.drynor.tools.osrs

import kotlin.test.*

class ZulrahHumanoidSkeletonTest {
    @Test fun baseZeroUsesNativeShadowAndSubmeshColumnsAndDonorTypeRemapping() {
        val raw=byteArrayOf(2,0,6,1,0,0x12,0x34,(-1).toByte(),(-1).toByte(),1,2,7,8,9)
        val base=ZulrahAnimationAssets.decodeSourceBase(0,777,raw)
        assertContentEquals(intArrayOf(0,2),base.types)
        assertContentEquals(intArrayOf(1,0),base.shadowed)
        assertContentEquals(intArrayOf(0x1234,65535),base.submeshes)
        assertContentEquals(intArrayOf(7),base.labels[0]); assertContentEquals(intArrayOf(8,9),base.labels[1])
        assertFailsWith<IllegalArgumentException> { ZulrahAnimationAssets.decodeSourceBase(0,777,raw+byteArrayOf(1)) }
        val osrs=ZulrahAnimationAssets.decodeSourceBase(5000,778,byteArrayOf(1,2,1,7))
        assertContentEquals(intArrayOf(0),osrs.shadowed); assertContentEquals(intArrayOf(65535),osrs.submeshes)
    }
}
