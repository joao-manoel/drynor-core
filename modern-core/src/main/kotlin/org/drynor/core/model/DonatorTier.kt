package org.drynor.core.model

import kotlinx.serialization.Serializable

/** Cosmetic supporter status, independent from moderation permissions. */
@Serializable
enum class DonatorTier(val displayName: String, val xpBonus: Double) {
    NONE("Player", 0.0),
    DONATOR("Donator", 0.05),
    SUPER("Super Donator", 0.10),
    EXTREME("Extreme Donator", 0.15),
    LEGENDARY("Legendary Donator", 0.20),
    VIP("VIP Donator", 0.25),
    SUPREME("Supreme Donator", 0.30);

    /** Supplied by the native chat-icon mapping; never use this as login rights. */
    val chatCrown: Int get() = if (this == NONE) 0 else ordinal + 3
}
