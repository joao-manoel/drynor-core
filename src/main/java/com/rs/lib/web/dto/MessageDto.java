package com.rs.lib.web.dto;

import com.rs.lib.game.Rights;
import com.rs.lib.model.Account;

public record MessageDto(Account source, Destination destination, String message) {

    public record Destination(Type type, String key) {
        public enum Type {
            FRIENDS_CHAT,
            CLAN_CHAT,
            GUEST_CLAN_CHAT,
            BROADCAST
        }

    }
}
