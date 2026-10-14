package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImInt
import imgui.type.ImString
import org.darkan.cs2.ir.VarSpace
import org.darkan.tools.rsinterface.InterfaceState

/**
 * Sets the game state an interface is previewed against: vars by gameval name, stat levels, and
 * the scripts to run once the load scripts have built the interface.
 */
class InterfaceStatePanel(
    private val state: InterfaceState,
    private val reloadScripts: () -> Unit,
    private val run: (InterfaceState.ScriptRun) -> String,
) {
    var visible: Boolean = true

    private var firstFrame = true

    private val varName = ImString(128)
    private val varValue = ImString(128)
    private val statId = ImInt(0)
    private val statLevel = ImInt(1)
    private val allStats = ImInt(0)
    private val scriptName = ImString(128)

    private var status = ""

    fun render(windowHeight: Int) {
        if (!visible) return
        if (firstFrame) {
            ImGui.setNextWindowSize(430f, (windowHeight * 0.45f).coerceAtLeast(280f), ImGuiCond.FirstUseEver)
            firstFrame = false
        }
        if (!ImGui.begin("State")) {
            ImGui.end()
            return
        }

        renderVariables()
        ImGui.separator()
        renderStats()
        ImGui.separator()
        renderRuns()
        ImGui.separator()

        if (ImGui.button("Re-run load scripts")) {
            reloadScripts()
            status = "load scripts queued"
        }
        ImGui.sameLine()
        if (ImGui.button("Clear state")) {
            state.clear()
            allStats.set(0)
            reloadScripts()
            status = "state cleared"
        }
        ImGuiText.wrapped(status)
        ImGui.end()
    }

    private fun renderVariables() {
        ImGui.text("Variables")
        ImGui.setNextItemWidth(180f)
        ImGui.inputTextWithHint("##varname", "varbit_skill_guide_skill_v2", varName)
        ImGui.sameLine()
        ImGui.setNextItemWidth(90f)
        val entered = ImGui.inputTextWithHint("##varvalue", "11", varValue, ENTER)
        ImGui.sameLine()
        if (ImGui.button("Set##var") || entered) {
            status = state.set(varName.get().trim(), varValue.get().trim())
            reloadScripts()
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("The vars.d.ts spelling: varp_, varbit_, varc_ or varcstr_ then the gameval name or the id.")
        }

        val variables = state.variables
        if (variables.isEmpty()) {
            ImGuiText.disabled("Nothing set; every var reads 0.")
            return
        }
        for ((index, variable) in variables.withIndex()) {
            val value = if (variable.space == VarSpace.VARC_STRING) "\"${variable.text}\"" else variable.value.toString()
            ImGui.text("${variable.label} = $value")
            ImGui.sameLine()
            if (ImGui.smallButton("clear##var$index")) {
                state.remove(variable)
                reloadScripts()
                status = "cleared ${variable.label}"
            }
        }
    }

    private fun renderStats() {
        ImGui.text("Stats")
        ImGui.setNextItemWidth(90f)
        if (ImGui.inputInt("every stat", allStats)) {
            state.defaultStat = allStats.get()
            reloadScripts()
        }
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("##statid", statId)
        ImGui.sameLine()
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("##statlevel", statLevel)
        ImGui.sameLine()
        if (ImGui.button("Set##stat")) {
            status = state.setStat("${statId.get()}=${statLevel.get()}")
            reloadScripts()
        }
        for ((stat, level) in state.stats.entries.toList()) {
            ImGui.text("stat $stat = $level")
            ImGui.sameLine()
            if (ImGui.smallButton("clear##stat$stat")) {
                state.removeStat(stat)
                reloadScripts()
            }
        }
    }

    private fun renderRuns() {
        ImGui.text("Run after every load")
        ImGui.setNextItemWidth(240f)
        val entered = ImGui.inputTextWithHint("##runname", "cs2_23 or cs2_23:1,2", scriptName, ENTER)
        ImGui.sameLine()
        if (ImGui.button("Add##run") || entered) {
            status = state.addRun(scriptName.get().trim())
            reloadScripts()
        }
        val runs = state.runs
        if (runs.isEmpty()) {
            ImGuiText.disabled("Only the interface's own load scripts run.")
            return
        }
        if (ImGui.collapsingHeader("Queued (${runs.size})", ImGuiTreeNodeFlags.DefaultOpen)) {
            for ((index, script) in runs.withIndex()) {
                val arguments = (script.ints.map { it.toString() } + script.strings).joinToString(", ")
                ImGui.text(if (arguments.isEmpty()) script.label else "${script.label}($arguments)")
                ImGui.sameLine()
                if (ImGui.smallButton("run##run$index")) {
                    status = run(script)
                }
                ImGui.sameLine()
                if (ImGui.smallButton("remove##run$index")) {
                    state.removeRun(script)
                    status = "removed ${script.label}"
                }
            }
        }
    }

    private companion object {
        const val ENTER = ImGuiInputTextFlags.EnterReturnsTrue
    }
}
