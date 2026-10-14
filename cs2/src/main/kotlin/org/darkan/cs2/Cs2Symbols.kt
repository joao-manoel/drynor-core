package org.darkan.cs2

import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace

/**
 * The default names for the things scripts refer to by id.
 *
 * Variables and scripts are emitted as ordinary identifiers rather than indexed
 * lookups so an editor can rename them. The default comes from the gameval
 * catalogs ([Cs2Names]), which are the only place a name lives; a renamed symbol
 * is resolved through the declarations written alongside the scripts instead.
 *
 * The numbered forms the decompiler emitted before the catalogs were wired in -
 * `varbit11654`, `script6559` - are still understood, so a folder dumped by an
 * older build keeps compiling.
 */
object Cs2Symbols {

    /** The prefixes the decompiler used before the catalogs named variables. */
    private val legacyPrefixes = mapOf(
        "varp" to VarSpace.VARP,
        "varbit" to VarSpace.VARBIT,
        "varc" to VarSpace.VARC,
        "varcstring" to VarSpace.VARC_STRING,
        "varn" to VarSpace.VARN,
        "varnbit" to VarSpace.VARNBIT,
        "clanvar" to VarSpace.CLAN,
        "clansetting" to VarSpace.CLAN_SETTING,
    )

    /** Longest first, so `varcstring12` does not read as `varc` + `string12`. */
    private val orderedPrefixes = legacyPrefixes.keys.sortedByDescending { it.length }

    private val numbered = Regex("^([a-z]+)(\\d+)(Old)?$")

    fun variableName(value: VarRef): String = Cs2Names.variableIdentifier(value)

    /** The variable an identifier names, or null when it is not one. */
    fun variableOf(name: String): VarRef? = Cs2Names.variableOf(name) ?: numberedVariable(name)

    /** The pre-gameval default, `varbit11654`, kept so older dumps still compile. */
    private fun numberedVariable(name: String): VarRef? {
        val match = numbered.matchEntire(name) ?: return null
        val prefix = orderedPrefixes.firstOrNull { name.startsWith(it) } ?: return null
        val id = name.removePrefix(prefix).removeSuffix("Old").toIntOrNull() ?: return null
        val space = legacyPrefixes[prefix] ?: return null
        val type = if (space == VarSpace.VARC_STRING) Cs2Type.STRING else Cs2Type.INT
        return VarRef(space, id, type, if (match.groupValues[3].isEmpty()) null else "old")
    }

    fun scriptName(id: Int): String = Cs2Names.scriptIdentifier(id)

    /** The script an identifier names, or null when it is not one. */
    fun scriptOf(name: String): Int? = numberedScript(name) ?: Cs2Names.scriptIdOf(name)

    /** The pre-gameval default, `script6559`. */
    private fun numberedScript(name: String): Int? =
        if (name.startsWith("script")) name.removePrefix("script").toIntOrNull() else null
}
