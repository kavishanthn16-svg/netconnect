package com.netconnect.common.contracts;

public interface MessageRouter {
    void sendGroup(Session sender, String text);

    void sendPrivate(Session sender, String toUser, String text);
}
