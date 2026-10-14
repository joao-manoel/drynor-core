package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol

/**
 * Text helpers that do not treat their argument as a printf format string.
 *
 * Only `ImGui.text` is safe in imgui-java: it binds to `ImGui::TextUnformatted`.
 * Every other text call passes the string straight through as the *format*
 * argument -
 *
 * ```
 * public static native void textDisabled(String text); //  ImGui::TextDisabled(text, NULL);
 * public static native void textWrapped(String text);  //  ImGui::TextWrapped(text, NULL);
 * public static native void textColored(int, String);  //  ImGui::TextColored(ImColor(col), text, NULL);
 * public static native void bulletText(String text);   //  ImGui::BulletText(text, NULL);
 * public static native void setTooltip(String text);   //  ImGui::SetTooltip(text, NULL);
 * public static native boolean treeNode(String strId, String label);
 *                                                      //  ImGui::TreeNode(strId, label, NULL);
 * ```
 *
 * Note that none of these have a varargs overload, so the usual C-side mitigation
 * of passing a literal `"%s"` with the text as an argument is not available here:
 * routing through these wrappers is the only fix.
 *
 * so a single `%` in the string makes the C runtime read a conversion argument
 * that was never pushed. In this editor that is not a theoretical hazard: the
 * panels display interface text, component names and script strings straight out
 * of the cache, any of which can contain a `%`, and the zoom control legitimately
 * wants to print one. The observed symptom is the label rendering as a fragment
 * of some unrelated string followed by a run of `?`, because the format walked
 * off into whatever happened to be in the varargs registers.
 *
 * These wrappers reproduce each style through the unformatted path instead, so
 * arbitrary text is always safe to display.
 */
object ImGuiText {

    /** Greyed-out text, safe for arbitrary content. */
    fun disabled(text: String) {
        pushDisabledColour()
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    /** Word-wrapped text, safe for arbitrary content. */
    fun wrapped(text: String) {
        // The zero wrap position is ImGui's "wrap at the window's right edge",
        // which is what TextWrapped does before delegating to Text.
        ImGui.pushTextWrapPos(0f)
        ImGui.text(text)
        ImGui.popTextWrapPos()
    }

    /** Coloured text, safe for arbitrary content. [colour] is ImGui's packed ABGR. */
    fun coloured(colour: Int, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, colour)
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    /** Coloured text from normalised components, safe for arbitrary content. */
    fun coloured(red: Float, green: Float, blue: Float, text: String) {
        coloured(red, green, blue, 1f, text)
    }

    /** Coloured text from normalised components with an explicit alpha. */
    fun coloured(red: Float, green: Float, blue: Float, alpha: Float, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, red, green, blue, alpha)
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    /**
     * A bulleted line, safe for arbitrary content.
     *
     * `ImGui::Bullet()` already ends with `SameLine(0, FramePadding.x * 2)`, which
     * is the same advance `BulletText` builds into its own item width, so this
     * lays out identically to the printf version.
     */
    fun bullet(text: String) {
        ImGui.bullet()
        ImGui.text(text)
    }

    /**
     * A tree node with an explicit id, safe for arbitrary content in [label].
     *
     * imgui-java has no unformatted `treeNode(strId, label)`, so the id is carried
     * in the label using ImGui's `###` marker, which makes the node's id depend
     * only on the text after the marker - the same decoupling the two-argument
     * native call provides. Callers must still `treePop()` when this returns true.
     */
    fun treeNode(id: String, label: String): Boolean {
        return ImGui.treeNode("$label###$id")
    }

    /**
     * A hover tooltip, safe for arbitrary content. `SetTooltip` is one of the
     * printf calls, and tooltips are where script errors and component names end
     * up, so it is the last place that should be formatting its argument.
     */
    fun tooltip(text: String) {
        ImGui.beginTooltip()
        // NOT `wrapped`: a tooltip window is auto-fitting, and "wrap at the window's
        // right edge" is resolved against a work rect that has no width yet, so every
        // tooltip collapsed into a one-character-per-line column. Dear ImGui's own
        // tooltip idiom is an explicit wrap width, which leaves short text on one line
        // and still wraps genuinely long text such as a script error.
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + ImGui.getFontSize() * 35f)
        ImGui.text(text)
        ImGui.popTextWrapPos()
        ImGui.endTooltip()
    }

    /** Coloured, word-wrapped text from normalised components. */
    fun colouredWrapped(red: Float, green: Float, blue: Float, alpha: Float, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, red, green, blue, alpha)
        wrapped(text)
        ImGui.popStyleColor()
    }

    /** Greyed-out, word-wrapped text. */
    fun disabledWrapped(text: String) {
        pushDisabledColour()
        wrapped(text)
        ImGui.popStyleColor()
    }

    /** Pushes the theme's disabled-text colour; the caller pops it. */
    private fun pushDisabledColour() {
        val colour = ImGui.getStyle().getColor(ImGuiCol.TextDisabled)
        ImGui.pushStyleColor(ImGuiCol.Text, colour.x, colour.y, colour.z, colour.w)
    }
}
