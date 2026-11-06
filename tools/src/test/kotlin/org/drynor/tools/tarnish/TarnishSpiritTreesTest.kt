package org.drynor.tools.tarnish

import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TarnishSpiritTreesTest {
    @Test fun nativeArgbOrientationCorrectionReversesRowsWithoutChangingPaletteOrSource() {
        val frame=IndexedGraphic(width=3,height=2,flags=0).also { it.raster=byteArrayOf(0,1,2,3,4,5);it.palette=intArrayOf(0,10,20,30,40,50) }
        val source=GraphicDefinition(20319,arrayOf(frame),frame.palette.copyOf(),false,3,2)
        val corrected=TarnishSpiritTrees.forNativeTexture(source)
        assertContentEquals(byteArrayOf(2,1,0,5,4,3),corrected.graphics!!.single().raster)
        assertContentEquals(byteArrayOf(0,1,2,3,4,5),source.graphics!!.single().raster)
        assertContentEquals(source.rawPalette,corrected.rawPalette)
        assertEquals(source,TarnishSpiritTrees.forNativeTexture(corrected))
    }
    @Test fun textureIndexPermitsSparseIdsButRequiresTheDeclaredFinalRecord() {
        val records=TarnishSpiritTrees.textureRecords(byteArrayOf(0,8,0,2,0,1,10,0,8,0,1,20))
        assertEquals(setOf(2,8),records.keys)
        assertContentEquals(byteArrayOf(20),records.getValue(8))
        assertFailsWith<IllegalArgumentException> { TarnishSpiritTrees.textureRecords(byteArrayOf(0,8,0,2,0,1,10)) }
    }
    @Test fun croppedBarkSpritePadsToSourceCanvasWithoutStretchingPixels() {
        val frame=IndexedGraphic(offsetX=1,offsetY=1,width=2,height=2).also {
            it.raster=byteArrayOf(1,2,3,4);it.palette=intArrayOf(0,10,20,30,40)
        }
        val source=GraphicDefinition(455,arrayOf(frame),frame.palette.copyOf(),false,4,3)
        val result=TarnishSpiritTrees.normalize(source,60000)
        assertEquals(60000,result.id)
        assertEquals(4,result.graphics!!.single().width)
        assertEquals(3,result.graphics!!.single().height)
        assertContentEquals(byteArrayOf(0,0,0,0,0,1,2,0,0,3,4,0),result.graphics!!.single().raster)
        assertContentEquals(frame.palette,result.rawPalette)
    }

    @Test fun unexpectedAlphaAndOutOfBoundsCroppingAreNotSilentlyDiscarded() {
        val frame=IndexedGraphic(width=1,height=1).also { it.raster=byteArrayOf(1);it.palette=intArrayOf(0,10) }
        val source=GraphicDefinition(455,arrayOf(frame),frame.palette.copyOf(),false,1,1)
        frame.alpha=byteArrayOf(1)
        assertFailsWith<IllegalArgumentException> { TarnishSpiritTrees.normalize(source,1) }
        frame.alpha=null;frame.offsetX=1
        assertFailsWith<IllegalArgumentException> { TarnishSpiritTrees.normalize(source,1) }
    }
}
