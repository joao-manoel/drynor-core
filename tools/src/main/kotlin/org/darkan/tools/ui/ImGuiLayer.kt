package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiDockNodeFlags
import imgui.flag.ImGuiStyleVar
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import org.darkan.tools.engine.window.Window

class ImGuiLayer(private val window: Window) {
    private val imGuiGlfw = ImGuiImplGlfw()
    private val imGuiGl3 = ImGuiImplGl3()

    private var dockspaceId = 0

    fun init() {
        ImGui.createContext()

        val io = ImGui.getIO()
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable)
        io.setIniFilename("darkan-tools-layout.ini")

        imGuiGlfw.init(window.handle, true)
        imGuiGl3.init("#version 330 core")
    }

    fun beginFrame() {
        imGuiGl3.newFrame()
        imGuiGlfw.newFrame()
        ImGui.newFrame()
    }

    fun beginDockspace() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f)
        dockspaceId = ImGui.dockSpaceOverViewport(
            0,
            ImGui.getMainViewport(),
            ImGuiDockNodeFlags.PassthruCentralNode,
        )
        ImGui.popStyleVar()
    }

    fun endDockspace() {
        dockspaceId = 0
    }

    fun dockspace(): Int = dockspaceId

    fun endFrame() {
        ImGui.render()
        imGuiGl3.renderDrawData(ImGui.getDrawData())
    }

    fun cleanup() {
        imGuiGl3.shutdown()
        imGuiGlfw.shutdown()
        ImGui.destroyContext()
    }

    fun wantCaptureKeyboard(): Boolean = ImGui.getIO().wantCaptureKeyboard

    fun wantCaptureMouse(): Boolean = ImGui.getIO().wantCaptureMouse

    fun applyEditorTheme() {
        val style = ImGui.getStyle()
        style.setWindowPadding(8f, 6f)
        style.setFramePadding(6f, 3f)
        style.setItemSpacing(6f, 4f)
        style.setItemInnerSpacing(5f, 4f)
        style.setCellPadding(4f, 2f)
        style.indentSpacing = 16f
        style.scrollbarSize = 12f
        style.grabMinSize = 10f

        style.windowBorderSize = 1f
        style.frameBorderSize = 0f
        style.windowRounding = 4f
        style.frameRounding = 3f
        style.grabRounding = 3f
        style.tabRounding = 3f
        style.scrollbarRounding = 3f

        colour(ImGuiCol.Text, 0.90f, 0.91f, 0.93f, 1.00f)
        colour(ImGuiCol.TextDisabled, 0.47f, 0.49f, 0.53f, 1.00f)
        colour(ImGuiCol.WindowBg, 0.11f, 0.11f, 0.12f, 0.97f)
        colour(ImGuiCol.ChildBg, 0.13f, 0.13f, 0.15f, 0.60f)
        colour(ImGuiCol.PopupBg, 0.09f, 0.09f, 0.10f, 0.98f)
        colour(ImGuiCol.Border, 0.24f, 0.25f, 0.28f, 0.70f)
        colour(ImGuiCol.FrameBg, 0.18f, 0.19f, 0.21f, 1.00f)
        colour(ImGuiCol.FrameBgHovered, 0.24f, 0.26f, 0.29f, 1.00f)
        colour(ImGuiCol.FrameBgActive, 0.28f, 0.31f, 0.35f, 1.00f)
        colour(ImGuiCol.TitleBg, 0.10f, 0.10f, 0.11f, 1.00f)
        colour(ImGuiCol.TitleBgActive, 0.15f, 0.17f, 0.20f, 1.00f)
        colour(ImGuiCol.TitleBgCollapsed, 0.10f, 0.10f, 0.11f, 0.80f)
        colour(ImGuiCol.MenuBarBg, 0.14f, 0.14f, 0.16f, 1.00f)
        colour(ImGuiCol.ScrollbarBg, 0.09f, 0.09f, 0.10f, 0.60f)
        colour(ImGuiCol.ScrollbarGrab, 0.28f, 0.29f, 0.32f, 1.00f)
        colour(ImGuiCol.ScrollbarGrabHovered, 0.35f, 0.37f, 0.41f, 1.00f)
        colour(ImGuiCol.ScrollbarGrabActive, 0.42f, 0.44f, 0.49f, 1.00f)
        colour(ImGuiCol.CheckMark, ACCENT_R, ACCENT_G, ACCENT_B, 1.00f)
        colour(ImGuiCol.SliderGrab, 0.35f, 0.62f, 0.74f, 1.00f)
        colour(ImGuiCol.SliderGrabActive, ACCENT_R, ACCENT_G, ACCENT_B, 1.00f)
        colour(ImGuiCol.Button, 0.21f, 0.22f, 0.25f, 1.00f)
        colour(ImGuiCol.ButtonHovered, 0.27f, 0.31f, 0.36f, 1.00f)
        colour(ImGuiCol.ButtonActive, 0.20f, 0.45f, 0.56f, 1.00f)
        colour(ImGuiCol.Header, 0.20f, 0.22f, 0.25f, 1.00f)
        colour(ImGuiCol.HeaderHovered, 0.25f, 0.31f, 0.37f, 1.00f)
        colour(ImGuiCol.HeaderActive, 0.20f, 0.45f, 0.56f, 1.00f)
        colour(ImGuiCol.Separator, 0.24f, 0.25f, 0.28f, 1.00f)
        colour(ImGuiCol.SeparatorHovered, 0.35f, 0.50f, 0.60f, 1.00f)
        colour(ImGuiCol.SeparatorActive, ACCENT_R, ACCENT_G, ACCENT_B, 1.00f)
        colour(ImGuiCol.ResizeGrip, 0.26f, 0.28f, 0.31f, 0.70f)
        colour(ImGuiCol.ResizeGripHovered, 0.35f, 0.50f, 0.60f, 0.90f)
        colour(ImGuiCol.ResizeGripActive, ACCENT_R, ACCENT_G, ACCENT_B, 1.00f)
        colour(ImGuiCol.Tab, 0.15f, 0.16f, 0.18f, 1.00f)
        colour(ImGuiCol.TabHovered, 0.26f, 0.33f, 0.39f, 1.00f)
        colour(ImGuiCol.TabSelected, 0.20f, 0.26f, 0.31f, 1.00f)
        colour(ImGuiCol.TabDimmed, 0.13f, 0.14f, 0.15f, 1.00f)
        colour(ImGuiCol.TabDimmedSelected, 0.17f, 0.18f, 0.20f, 1.00f)
        colour(ImGuiCol.DockingPreview, ACCENT_R, ACCENT_G, ACCENT_B, 0.35f)
        colour(ImGuiCol.DockingEmptyBg, 0.09f, 0.09f, 0.10f, 0.00f)
        colour(ImGuiCol.TableHeaderBg, 0.17f, 0.18f, 0.20f, 1.00f)
        colour(ImGuiCol.TableBorderStrong, 0.26f, 0.27f, 0.30f, 1.00f)
        colour(ImGuiCol.TableBorderLight, 0.20f, 0.21f, 0.23f, 1.00f)
        colour(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f)
        colour(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.03f)
        colour(ImGuiCol.TextSelectedBg, ACCENT_R, ACCENT_G, ACCENT_B, 0.35f)
        colour(ImGuiCol.NavCursor, ACCENT_R, ACCENT_G, ACCENT_B, 1.00f)
    }

    private fun colour(target: Int, red: Float, green: Float, blue: Float, alpha: Float) {
        ImGui.getStyle().setColor(target, red, green, blue, alpha)
    }

    companion object {
        private const val ACCENT_R = 0.18f
        private const val ACCENT_G = 0.82f
        private const val ACCENT_B = 1.00f
    }
}
