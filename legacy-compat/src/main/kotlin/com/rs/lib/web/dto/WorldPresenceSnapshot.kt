package com.rs.lib.web.dto

import com.rs.lib.game.WorldInfo

/** Additive recovery over legacy HTTP; does not prune or steal another world's presence. */
data class WorldPresenceSnapshot(val world: WorldInfo, val usernames: List<String>)
