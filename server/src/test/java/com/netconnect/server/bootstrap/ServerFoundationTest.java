package com.netconnect.server.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServerFoundationTest {
    @Test
    void launcherHasAnApplicationEntryPoint() throws NoSuchMethodException {
        assertNotNull(NetConnectServer.class.getMethod("main", String[].class));
    }
}
