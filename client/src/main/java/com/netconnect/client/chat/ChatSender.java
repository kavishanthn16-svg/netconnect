package com.netconnect.client.chat;

/**
 * Outbound transport supplied by the shell/security owners (their TLS control connection).
 * {@link ChatPane} calls these on a background thread, never on the JavaFX thread, so an
 * implementation may block. Throwing reports the failure to the user.
 */
public interface ChatSender {
    void sendGroup(String text) throws Exception;

    void sendPrivate(String toUser, String text) throws Exception;
}
