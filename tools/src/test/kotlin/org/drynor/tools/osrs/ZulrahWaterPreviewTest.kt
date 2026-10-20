package org.drynor.tools.osrs

import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.data.MapEnvironmentEffect
import world.gregs.voidps.cache.definition.decoder.MapEffectDecoder
import kotlin.test.*

class ZulrahWaterPreviewTest {
    @Test fun swampMetadataUsesGoldenNativeOrderWithoutChangingSurfaceTiles() {
        val surface = ByteArray(16384)
        val result = ZulrahWaterPreview.swampAtmosphere(surface,8751)
        assertContentEquals(surface,result.copyOfRange(0,16384))
        val golden = byteArrayOf(0,111,0,198.toByte(),203.toByte(),164.toByte(),1,67,1,5,0,199.toByte(),0,83,91,62,6,106)
        assertContentEquals(golden,result.copyOfRange(16384,result.size))
        val effect = MapEffectDecoder.decode(result,16384,8751).single() as MapEnvironmentEffect
        assertEquals(323,effect.sunAmbient); assertEquals(261,effect.sunLight); assertEquals(199,effect.sunBacklight)
        assertEquals(5462846,effect.fogColour); assertEquals(1642,effect.fogDepth)
    }
    @Test fun swampFallbackDoesNotOverwriteNativeOrExistingEnvironment() {
        assertFailsWith<IllegalArgumentException> { ZulrahWaterPreview.swampAtmosphere(ByteArray(16384),8496) }
        val withTail = ZulrahWaterPreview.swampAtmosphere(ByteArray(16384),9008)
        assertFailsWith<IllegalArgumentException> { ZulrahWaterPreview.swampAtmosphere(withTail,9008) }
    }
    @Test fun goldenDepthTerminatesEachTileAndRepresents992NativeUnits() {
        val golden = ByteArray(8192) { if(it % 2 == 0) 1 else 31 }
        val map = ZulrahWaterPreview.decodeStrict(golden,8751)
        assertEquals(1,map.levels)
        assertEquals(992,MapTile(map.tiles[0]).height * 32)
        assertEquals(31,MapTile(map.tiles[4095]).height)
        assertContentEquals(golden,ZulrahWaterPreview.encode(map))
    }
    @Test fun underwaterCopiesFloorGeometryAndRemovesSurfaceFlags() {
        val surface = MapDefinition(8751)
        surface.tiles[0]=MapTile(120,13,114,2,3,7,63).packed
        val result=ZulrahWaterPreview.underwater(surface)
        val tile=MapTile(result.tiles[0])
        assertEquals(114,tile.overlayId); assertEquals(63,tile.underlayId)
        assertEquals(2,tile.overlayPath); assertEquals(3,tile.overlayRotation)
        assertEquals(0,tile.settings); assertEquals(31,tile.height)
        val bytes=ZulrahWaterPreview.encode(result)
        assertContentEquals(byteArrayOf(13,114,144.toByte(),1,31),bytes.copyOf(5))
    }
    @Test fun incompleteTilePayloadAndTailAreRejected() {
        assertFailsWith<IllegalArgumentException>{ZulrahWaterPreview.decodeStrict(ByteArray(4095),8751)}
        assertFailsWith<IllegalArgumentException>{ZulrahWaterPreview.decodeStrict(ByteArray(4095)+byteArrayOf(1),8751)}
        assertFailsWith<IllegalArgumentException>{ZulrahWaterPreview.decodeStrict(ByteArray(4097),8751)}
    }
}
