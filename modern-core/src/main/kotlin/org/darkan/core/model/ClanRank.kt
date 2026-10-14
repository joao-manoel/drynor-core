package org.darkan.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ClanRank(val iconId: Int) {
    NONE(-1),
    RECRUIT(0),
    CORPORAL(1),
    SERGEANT(2),
    LIEUTENANT(3),
    CAPTAIN(4),
    GENERAL(5),
    ADMIN(100),
    ORGANIZER(101),
    COORDINATOR(102),
    OVERSEER(103),
    DEPUTY_OWNER(125),
    OWNER(126),
    JMOD(127);

    companion object {
        private val byIconId = entries.associateBy { it.iconId }
        fun fromIconId(id: Int): ClanRank = byIconId[id] ?: NONE
    }
}
