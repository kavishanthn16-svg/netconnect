package com.netconnect.client.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Pure (non-JavaFX) message store: de-duplicates by messageId and splits group/private views. */
public final class ChatConversationModel {
    public static final int MAX_HISTORY = 1000;

    private final String self;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Set<UUID> seen = new HashSet<>();

    public ChatConversationModel(String self) {
        this.self = java.util.Objects.requireNonNull(self, "self");
    }

    /** @return false if the message was a duplicate or not addressed to/from this user. */
    public synchronized boolean add(ChatMessage message) {
        if (message.scope() == ChatMessage.Scope.PRIVATE
                && !self.equalsIgnoreCase(message.fromUser()) && !self.equalsIgnoreCase(message.toUser())) {
            return false;
        }
        if (!seen.add(message.messageId())) {
            return false;
        }
        messages.add(message);
        if (messages.size() > MAX_HISTORY) {
            seen.remove(messages.remove(0).messageId());
        }
        return true;
    }

    public synchronized List<ChatMessage> group() {
        return messages.stream().filter(m -> m.scope() == ChatMessage.Scope.GROUP).toList();
    }

    public synchronized List<ChatMessage> privateWith(String peer) {
        if (peer == null) {
            return List.of();
        }
        return messages.stream()
                .filter(m -> m.scope() == ChatMessage.Scope.PRIVATE && peer.equalsIgnoreCase(m.peerOf(self)))
                .toList();
    }

    /** Peers that have a private conversation, alphabetically. */
    public synchronized Set<String> privatePeers() {
        Set<String> peers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        messages.stream().filter(m -> m.scope() == ChatMessage.Scope.PRIVATE)
                .forEach(m -> peers.add(m.peerOf(self)));
        return peers;
    }

    public String self() {
        return self;
    }
}
