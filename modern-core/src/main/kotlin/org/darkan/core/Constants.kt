package org.darkan.core

object Constants {
    const val ATTACK = 0
    const val DEFENSE = 1
    const val STRENGTH = 2
    const val HITPOINTS = 3
    const val RANGE = 4
    const val PRAYER = 5
    const val MAGIC = 6
    const val COOKING = 7
    const val WOODCUTTING = 8
    const val FLETCHING = 9
    const val FISHING = 10
    const val FIREMAKING = 11
    const val CRAFTING = 12
    const val SMITHING = 13
    const val MINING = 14
    const val HERBLORE = 15
    const val AGILITY = 16
    const val THIEVING = 17
    const val SLAYER = 18
    const val FARMING = 19
    const val RUNECRAFTING = 20
    const val HUNTER = 21
    const val CONSTRUCTION = 22
    const val SUMMONING = 23
    const val DUNGEONEERING = 24

    @JvmField
    val SKILL_NAME = arrayOf(
        "Attack", "Defence", "Strength", "Constitution", "Ranged",
        "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching",
        "Fishing", "Firemaking", "Crafting", "Smithing", "Mining",
        "Herblore", "Agility", "Thieving", "Slayer", "Farming",
        "Runecrafting", "Hunter", "Construction", "Summoning", "Dungeoneering"
    )

    const val WORLD_CYCLE_NS = 600_000_000L
    const val WORLD_CYCLE_MS = WORLD_CYCLE_NS / 1_000_000L
    const val PACKET_SIZE_LIMIT = 7500
}
