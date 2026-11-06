package org.drynor.tools.tarnish

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class TarnishModelsTest {
    @Test fun staticType3PreservesBodyAndDropsOnlyExtraFooterFields() {
        val body=byteArrayOf(1,2,3,4)
        val footer=ByteArray(26).also { it[1]=2; it[3]=1; it[24]=-1; it[25]=-3 }
        val converted=TarnishModels.nativeBytes(body+footer)
        assertContentEquals(body+footer.copyOfRange(0,11)+footer.copyOfRange(12,22)+byteArrayOf(-1,-1),converted)
    }
    @Test fun refusesWeightedBonesInsteadOfLosingAnimationData() {
        val footer=ByteArray(26).also { it[11]=1; it[24]=-1; it[25]=-3 }
        assertFailsWith<IllegalArgumentException> { TarnishModels.nativeBytes(footer) }
    }
    @Test fun refusesUnexpectedSkinSection() {
        val footer=ByteArray(26).also { it[23]=1; it[24]=-1; it[25]=-3 }
        assertFailsWith<IllegalArgumentException> { TarnishModels.nativeBytes(footer) }
    }
}
