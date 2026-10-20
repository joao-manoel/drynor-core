package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import kotlin.test.*

class ZulrahLiveAssetsTest {
    private fun bytes(value:ObjectDefinition)=BufferWriter(4096).also { with(ObjectEncoder()) { it.encode(value) } }.toArray()
    private fun read(bytes:ByteArray)=ObjectDefinition(110658).also { ObjectDecoder().readLoop(it,BufferReader(bytes)) }

    @Test fun decodedStaticObjectAcceptsNativeAnimationWithoutLosingSceneFields() {
        val original=ObjectDefinition(110658,name="Torch",sizeX=2,sizeY=3,
            modelIds=arrayOf(intArrayOf(205514)),modelTypes=byteArrayOf(10),offsetX=-12,offsetY=8,offsetZ=4)
        val staticBytes=bytes(original)
        val decoded=read(staticBytes)
        assertNull(decoded.animations)
        ZulrahLiveAssets.restoreAnimation(decoded,20480)
        val animated=read(bytes(decoded))
        assertContentEquals(intArrayOf(20480),animated.animations)
        assertEquals(20480,animated.singleAnimation)
        // Removing only the new animation must recover every pre-existing encoded field.
        animated.animations=null;animated.singleAnimation=-1
        assertContentEquals(staticBytes,bytes(animated))
        assertFailsWith<IllegalArgumentException> { ZulrahLiveAssets.restoreAnimation(decoded,65535) }
    }
}
