package org.drynor.tools.tarnish

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class TarnishLegacyModelsTest {
    @Test fun staticType2PreservesBodyAndRemovesOnlyCheckedFooterExtension() {
        val body = byteArrayOf(1, 2, 3, 4)
        val footer = ByteArray(23).also { it[1] = 2; it[3] = 1; it[21] = -1; it[22] = -2 }
        assertContentEquals(body + footer.copyOfRange(0, 10) + footer.copyOfRange(11, 19), TarnishLegacyModels.nativeBytes(body + footer))
    }
    @Test fun weightedBonesAreNeverSilentlyDropped() {
        val footer = ByteArray(23).also { it[10] = 1; it[21] = -1; it[22] = -2 }
        assertFailsWith<IllegalArgumentException> { TarnishLegacyModels.nativeBytes(footer) }
    }
    @Test fun inconsistentSkinLengthFailsClosed() {
        val footer = ByteArray(23).also { it[20] = 1; it[21] = -1; it[22] = -2 }
        assertFailsWith<IllegalArgumentException> { TarnishLegacyModels.nativeBytes(footer) }
    }
}
