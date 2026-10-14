package org.darkan.core.model

/**
 * The clan permissions the settings interface displays, keyed by the varc the client shows each one
 * in. Which ranks hold a permission comes from the per-rank `<RANK>_PERMISSION_<ACTION>` clan
 * settings; the chat permissions come from the clan's cc talk/kick ranks instead.
 */
enum class ClanPermission(val varc: Int) {
    KICK_FROM_CC(1570) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.chatPermission(clan.ccKickRank)
    },
    TALK_IN_CC(1571) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.chatPermission(clan.ccChatRank)
    },
    LOCK_KEEP(1572) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.adminSetting(clan, "LOCK_KEEP")
    },
    ALLOWED_IN_KEEP(1573) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.setting(clan, "ENTER_KEEP")
    },
    LOCK_CITADEL(1574) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.adminSetting(clan, "LOCK_CITADEL")
    },
    ALLOWED_IN_CITADEL(1575) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.setting(clan, "ENTER_CITADEL")
    },
    INVITE(1576) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.setting(clan, "INVITE")
    },
    START_BATTLE(1577) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.setting(clan, "START_BATTLE")
    },
    LEAD_CLANWARS(1578) {
        override fun hasPermission(clan: Clan, rank: ClanRank) = rank.setting(clan, "LEAD_CLANWARS")
    };

    abstract fun hasPermission(clan: Clan, rank: ClanRank): Boolean

    companion object {
        private fun ClanRank.chatPermission(required: ClanRank) = when (this) {
            ClanRank.NONE -> false
            ClanRank.OWNER, ClanRank.JMOD -> true
            else -> ordinal >= required.ordinal
        }

        /** The rank's own `<RANK>_PERMISSION_<action>` setting; owners always have everything. */
        private fun ClanRank.setting(clan: Clan, action: String): Boolean = when (this) {
            ClanRank.NONE -> false
            ClanRank.OWNER, ClanRank.JMOD -> true
            else -> {
                val setting = ClanSetting.forName("${name}_PERMISSION_$action") ?: return false
                (clan.getSetting(setting) as? Number)?.toInt() == 1
            }
        }

        /** Permissions the client only offers to admin ranks and above. */
        private fun ClanRank.adminSetting(clan: Clan, action: String): Boolean =
            if (ordinal < ClanRank.ADMIN.ordinal) false else setting(clan, action)
    }
}
