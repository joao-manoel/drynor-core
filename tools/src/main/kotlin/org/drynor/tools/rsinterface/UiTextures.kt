package org.drynor.tools.rsinterface

import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_REPEAT
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.system.MemoryUtil

/**
 * GL texture helpers for the 2D interface pass.
 *
 * Interface art is blitted at, or close to, 1:1 so everything is uploaded with
 * nearest filtering and no mipmaps - the opposite of the 3D texture path, which
 * wants trilinear filtering. Rows are uploaded top-first so that `v = 0` is the
 * top of the image, matching [org.drynor.tools.engine.render.Canvas2D]'s
 * top-left origin.
 */
object UiTextures {

    /**
     * Uploads an ARGB pixel array as an RGBA texture.
     *
     * @param repeat wrap mode; tiled component graphics rely on GL_REPEAT so a
     *   whole tiled fill is a single quad instead of one quad per tile.
     */
    fun createArgb(pixels: IntArray, width: Int, height: Int, repeat: Boolean = false): Int {
        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)

        val wrap = if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            for (index in 0 until width * height) {
                val pixel = pixels[index]
                buffer.put(((pixel shr 16) and 0xff).toByte())
                buffer.put(((pixel shr 8) and 0xff).toByte())
                buffer.put((pixel and 0xff).toByte())
                buffer.put(((pixel ushr 24) and 0xff).toByte())
            }
            buffer.flip()
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        return textureId
    }
}
