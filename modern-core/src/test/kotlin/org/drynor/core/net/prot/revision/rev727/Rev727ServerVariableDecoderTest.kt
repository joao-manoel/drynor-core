package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.net.prot.assertServerRoundTrip
import org.drynor.core.net.prot.packet.*
import kotlin.test.Test
import kotlin.test.assertIs

class Rev727ServerVariableDecoderTest {
    private val codec = register727()
    private val smallValues = listOf(-128, -1, 0, 1, 127)
    private val largeValues = listOf(Int.MIN_VALUE, -129, 128, 65536, Int.MAX_VALUE)

    @Test
    fun `small variants keep every signed byte value`() {
        for (value in smallValues) {
            codec.assertServerRoundTrip(VarpSmall(65535, value))
            codec.assertServerRoundTrip(ClientSetVarcSmall(40000, value))
            codec.assertServerRoundTrip(VarbitSmall(32768, value))
            codec.assertServerRoundTrip(VarclanSetByte(0, value))
        }
    }

    @Test
    fun `large variants keep negative and full int values`() {
        for (value in largeValues + smallValues) {
            codec.assertServerRoundTrip(VarpLarge(65535, value))
            codec.assertServerRoundTrip(ClientSetVarcLarge(40000, value))
            codec.assertServerRoundTrip(VarbitLarge(32768, value))
            codec.assertServerRoundTrip(VarclanSetInt(0, value))
        }
    }

    @Test
    fun `packet factories pick a variant that round trips at the small large boundary`() {
        for (value in listOf(-129, -128, 127, 128)) {
            codec.assertServerRoundTrip(setVarpPacket(7, value))
            codec.assertServerRoundTrip(setVarcPacket(7, value))
            codec.assertServerRoundTrip(setVarbitPacket(7, value))
        }
        assertIs<VarpSmall>(setVarpPacket(7, 127))
        assertIs<VarpLarge>(setVarpPacket(7, 128))
    }

    @Test
    fun `string variants keep empty and long strings`() {
        codec.assertServerRoundTrip(ClientSetVarcStrSmall(65535, ""))
        codec.assertServerRoundTrip(ClientSetVarcStrSmall(1, "a".repeat(124)))
        codec.assertServerRoundTrip(ClientSetVarcStrLarge(65535, ""))
        codec.assertServerRoundTrip(ClientSetVarcStrLarge(40000, "b".repeat(1000)))
        codec.assertServerRoundTrip(setVarcStrPacket(3, "c".repeat(200)))
        codec.assertServerRoundTrip(VarclanSetString(65535, ""))
        codec.assertServerRoundTrip(VarclanSetString(1, "Clan £ name"))
    }

    @Test
    fun `clan long keeps the full 64 bit range`() {
        for (value in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            codec.assertServerRoundTrip(VarclanSetLong(65535, value))
        }
    }
}
