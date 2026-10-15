package org.drynor.tools.rsinterface

import org.drynor.core.gameval.gen.Fontmetrics
import org.drynor.tools.engine.render.Canvas2D
import org.drynor.tools.engine.render.ObjIconFont
import org.drynor.tools.engine.render.ObjIcons

class RsObjIconFont(
    private val fonts: FontCache,
    private val fontId: Int = Fontmetrics.p11_full
) : ObjIconFont {

    override fun draw(canvas: Canvas2D, text: String, x: Int, y: Int, colour: Int, shadow: Int) {
        val font = fonts.font(fontId) ?: return
        // The client's renderPlain measures y from the baseline, drawText from the box top.
        font.drawText(
            canvas, text, x, y - font.definition.topPadding,
            ObjIcons.WIDTH, 0, colour, shadow, 0, 0, 0, 0
        )
    }
}
