package org.drynor.tools.rsinterface

import org.drynor.cs2.Cs2Names
import org.drynor.cs2.ir.VarSpace

/**
 * The game state an interface is previewed against, and the scripts that drive it.
 *
 * Most interfaces draw nothing on their own: the skill guide builds its list from
 * `varbit_skill_guide_skill_v2` and a stat level, and a tab only fills in once the script the
 * client would have called has run. Neither exists in an editor with no player attached, so this
 * is where a developer says what to pretend - by gameval name, the same one the TypeScript uses.
 *
 * [runs] are re-applied after every load-script pass, because a reload re-seeds every component
 * and would otherwise throw away everything the driving script did.
 */
class InterfaceState(private val host: InterfaceCs2Host) {

    class Variable(val space: VarSpace, val id: Int, val label: String, val value: Int, val text: String)

    class ScriptRun(val scriptId: Int, val label: String, val ints: List<Int>, val strings: List<String>)

    private val entries = ArrayList<Variable>()
    private val scripts = ArrayList<ScriptRun>()

    val variables: List<Variable> get() = entries
    val runs: List<ScriptRun> get() = scripts

    val stats: Map<Int, Int> get() = host.stats
    var defaultStat: Int
        get() = host.defaultStat
        set(value) {
            host.defaultStat = value
        }

    fun set(assignment: String): String {
        val separator = assignment.indexOf('=')
        if (separator <= 0) {
            return "expected <var>=<value>, got '$assignment'"
        }
        return set(assignment.substring(0, separator).trim(), assignment.substring(separator + 1).trim())
    }

    fun set(name: String, value: String): String {
        val reference = Cs2Names.variableOf(name)
            ?: return "no variable named '$name'; use the vars.d.ts spelling, varbit_<name> or varbit_<id>"
        if (reference.space == VarSpace.VARC_STRING) {
            return set(Variable(reference.space, reference.id, name, 0, value))
        }
        val number = value.toIntOrNull() ?: return "'$value' is not a number"
        return set(Variable(reference.space, reference.id, name, number, ""))
    }

    fun set(variable: Variable): String {
        entries.removeAll { it.space == variable.space && it.id == variable.id }
        entries.add(variable)
        apply(variable)
        return "${variable.label} = ${describe(variable)}"
    }

    fun remove(variable: Variable) {
        entries.remove(variable)
        apply(Variable(variable.space, variable.id, variable.label, 0, ""))
    }

    fun setStat(assignment: String): String {
        val separator = assignment.indexOf('=')
        if (separator <= 0) {
            return "expected <stat>=<level> or all=<level>, got '$assignment'"
        }
        val target = assignment.substring(0, separator).trim()
        val level = assignment.substring(separator + 1).trim().toIntOrNull()
            ?: return "'${assignment.substring(separator + 1)}' is not a level"
        if (target.equals(ALL, ignoreCase = true)) {
            host.defaultStat = level
            return "every stat = $level"
        }
        val stat = target.toIntOrNull() ?: return "'$target' is not a stat id"
        host.stats[stat] = level
        return "stat $stat = $level"
    }

    fun removeStat(stat: Int) {
        host.stats.remove(stat)
    }

    fun addRun(specification: String): String {
        val separator = specification.indexOf(':')
        val name = (if (separator < 0) specification else specification.substring(0, separator)).trim()
        val scriptId = Cs2Names.scriptIdOf(name) ?: name.toIntOrNull()
            ?: return "no clientscript named '$name'"
        val ints = ArrayList<Int>()
        val strings = ArrayList<String>()
        if (separator >= 0) {
            for (argument in specification.substring(separator + 1).split(',')) {
                val trimmed = argument.trim()
                if (trimmed.isEmpty()) continue
                val number = trimmed.toIntOrNull()
                if (number == null) strings.add(trimmed) else ints.add(number)
            }
        }
        val label = Cs2Names.scriptIdentifier(scriptId)
        scripts.removeAll { it.scriptId == scriptId && it.ints == ints && it.strings == strings }
        scripts.add(ScriptRun(scriptId, label, ints, strings))
        return "will run $label after every load"
    }

    fun removeRun(run: ScriptRun) {
        scripts.remove(run)
    }

    fun clear() {
        entries.clear()
        scripts.clear()
        host.clearVariables()
        host.clearStats()
    }

    private fun apply(variable: Variable) = when (variable.space) {
        VarSpace.VARP -> host.setVarp(variable.id, variable.value)
        VarSpace.VARBIT -> host.setVarbit(variable.id, variable.value)
        VarSpace.VARC -> host.setVarc(variable.id, variable.value)
        VarSpace.VARC_STRING -> host.setVarcString(variable.id, variable.text)
        else -> Unit
    }

    private fun describe(variable: Variable): String =
        if (variable.space == VarSpace.VARC_STRING) "\"${variable.text}\"" else variable.value.toString()

    private companion object {
        const val ALL = "all"
    }
}
