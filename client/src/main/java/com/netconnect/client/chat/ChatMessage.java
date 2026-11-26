package com.netconnect.client.chat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One delivered chat message (CHAT_DELIVER payload). {@code toUser} is null for GROUP scope. */
public record ChatMessage(UUID messageId, String fromUser, String toUser, Scope scope, String text, Instant sentAt) {
    public enum Scope { GROUP, PRIVATE }

    public ChatMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(fromUser, "fromUser");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sentAt, "sentAt");
        if (scope == Scope.PRIVATE && toUser == null) {
            throw new IllegalArgumentException("PRIVATE message requires toUser");
        }
    }

    /** The other party of a private conversation as seen by {@code self}; null for group messages. */
    public String peerOf(String self) {
        if (scope != Scope.PRIVATE) {
            return null;
        }
        return fromUser.equalsIgnoreCase(self) ? toUser : fromUser;
    }
}
