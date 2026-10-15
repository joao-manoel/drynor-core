package org.drynor.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import org.drynor.tools.engine.async.LoadPhase
import org.drynor.tools.engine.async.LoadProgress

/**
 * Full-screen ImGui loading overlay shown during initial region loading.
 */
class LoadingScreen {
    fun render(progress: LoadProgress, windowWidth: Int, windowHeight: Int) {
        // Full-screen dark overlay
        ImGui.setNextWindowPos(0f, 0f)
        ImGui.setNextWindowSize(windowWidth.toFloat(), windowHeight.toFloat())
        val flags = ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or
                    ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoScrollbar or
                    ImGuiWindowFlags.NoInputs or ImGuiWindowFlags.NoBackground

        if (ImGui.begin("##loading_overlay", flags)) {
            // Center the loading info
            val centerX = windowWidth / 2f
            val centerY = windowHeight / 2f

            // Phase text
            val phaseText = when (progress.phase) {
                LoadPhase.IDLE -> "Initializing..."
                LoadPhase.TERRAIN -> "Loading terrain..."
                LoadPhase.OBJECTS -> "Loading objects..."
                LoadPhase.BATCHING -> "Building batches..."
                LoadPhase.GPU_UPLOAD -> "Uploading to GPU..."
                LoadPhase.DONE -> "Done!"
            }

            // Draw centered text
            val textWidth = ImGui.calcTextSize(phaseText).x
            ImGui.setCursorPos(centerX - textWidth / 2, centerY - 30)
            ImGui.text(phaseText)

            // Progress bar
            val barWidth = 300f
            ImGui.setCursorPos(centerX - barWidth / 2, centerY)
            ImGui.progressBar(progress.progress, barWidth, 20f,
                "%d / %d regions".format(progress.regionsReady, progress.totalRegions))

            // Pending GPU tasks
            if (progress.pendingGpuTasks > 0) {
                val gpuText = "${progress.pendingGpuTasks} GPU tasks pending"
                val gpuWidth = ImGui.calcTextSize(gpuText).x
                ImGui.setCursorPos(centerX - gpuWidth / 2, centerY + 30)
                ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, gpuText)
            }
        }
        ImGui.end()
    }
}
