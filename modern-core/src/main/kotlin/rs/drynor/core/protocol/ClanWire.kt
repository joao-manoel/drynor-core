package rs.drynor.core.protocol

import org.drynor.core.clan.ClanPackets
import org.drynor.core.formatPlayerNameForDisplay
import org.drynor.core.model.Clan
import org.drynor.core.net.prot.dto.ClanVarSetting
import org.drynor.core.net.prot.packet.ClanSettingsFull
import rs.drynor.core.persistence.ClanJson

/** Packs settings according to revision 727's cache definitions, including historic saved flags. */
object ClanWire {
    @JvmStatic fun memberUsernames(clan: Clan): List<String> = clan.members.entries
        .sortedWith(compareBy<Map.Entry<String, org.drynor.core.model.ClanMemberData>> { it.value.joinDate }.thenBy { it.key })
        .map { it.key }

    fun settings(
        clan: Clan, main: Boolean, version: Int = 0,
        displayName: (String) -> String = { it.formatPlayerNameForDisplay() },
        packedSettings: Map<Int, Any> = ClanJson().wireSettings(clan),
    ): ClanSettingsFull = ClanPackets.settingsFull(clan, main, version, displayName).copy(
        settings = packedSettings.entries.sortedBy { it.key }.map { (id, value) ->
            when (value) {
                is Int -> ClanVarSetting(key = id, intValue = value)
                is Long -> ClanVarSetting(key = id, longValue = value)
                is String -> ClanVarSetting(key = id, stringValue = value)
                else -> error("Unsupported clan setting value for $id")
            }
        }.toTypedArray()
    )
}
