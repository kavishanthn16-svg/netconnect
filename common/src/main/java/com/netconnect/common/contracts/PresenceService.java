package com.netconnect.common.contracts;

public interface PresenceService {
    void start();

    void registerEndpoint(Session session, int udpPort);

    void stop();
}
