package org.drynor.tools.engine.render

import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8
import org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.GL_RENDERBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBindRenderbuffer
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glDeleteRenderbuffers
import org.lwjgl.opengl.GL30.glFramebufferRenderbuffer
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGenRenderbuffers
import org.lwjgl.opengl.GL30.glRenderbufferStorage

/**
 * An off-screen colour + depth/stencil target.
 *
 * The interface editor draws the interface into one of these rather than into
 * the back buffer, which is what lets the render become an ordinary texture the
 * UI can place inside a dockable panel, scale for a zoom control, and letterbox
 * without the interface's own coordinate space changing. Drawing straight to the
 * back buffer ties the interface's pixels to the window's, so any panel docked
 * over it either covers the render or has to be kept out of the way by hand.
 *
 * Depth is attached because interface components can host 3D model viewports,
 * which clear and test depth inside their own scissor box.
 */
class RenderTarget(
    /** Sample the colour texture with nearest filtering, for crisp pixel art at integer zoom. */
    private val nearest: Boolean = true
) {
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** The colour attachment, usable as a texture once [end] has been called. */
    var textureId: Int = 0
        private set

    private var framebuffer = 0
    private var depthBuffer = 0

    /** True once a size has been allocated and the framebuffer validated. */
    val valid: Boolean get() = framebuffer != 0 && width > 0 && height > 0

    /**
     * Allocates or reallocates the attachments for a [width] x [height] target.
     * Cheap to call every frame: it only does work when the size actually changed.
     * Returns false when the requested size is degenerate or the driver rejects
     * the framebuffer, in which case nothing was bound and the caller must not
     * draw.
     */
    fun resize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }
        if (width == this.width && height == this.height && framebuffer != 0) {
            return true
        }
        this.width = width
        this.height = height

        if (framebuffer == 0) {
            framebuffer = glGenFramebuffers()
        }
        if (textureId == 0) {
            textureId = glGenTextures()
        }
        if (depthBuffer == 0) {
            depthBuffer = glGenRenderbuffers()
        }

        val filter = if (nearest) GL_NEAREST else GL_LINEAR
        glBindTexture(GL_TEXTURE_2D, textureId)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        // Clamping matters when the panel scales the texture: a repeating wrap
        // bleeds the opposite edge into the border at non-integer zoom.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)

        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height)
        glBindRenderbuffer(GL_RENDERBUFFER, 0)

        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthBuffer)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        if (status != GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("Render target ${width}x$height incomplete: status $status")
            cleanup()
            return false
        }
        return true
    }

    /**
     * Binds the target and sets the viewport to cover it. The caller is
     * responsible for clearing; [Canvas2D.begin] should be given this target's
     * size so its scissor arithmetic lines up.
     */
    fun begin() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glViewport(0, 0, width, height)
    }

    /** Unbinds the target and restores the viewport to a [windowWidth] x [windowHeight] back buffer. */
    fun end(windowWidth: Int, windowHeight: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, windowWidth, windowHeight)
    }

    fun cleanup() {
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer)
            framebuffer = 0
        }
        if (depthBuffer != 0) {
            glDeleteRenderbuffers(depthBuffer)
            depthBuffer = 0
        }
        if (textureId != 0) {
            glDeleteTextures(textureId)
            textureId = 0
        }
        width = 0
        height = 0
    }
}
