package com.netconnect.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetConnectConfigTest {
    @Test
    void usesSrsDefaults() {
        NetConnectConfig config = NetConnectConfig.defaults();

        assertEquals(5050, config.controlPort());
        assertEquals(5051, config.udpPort());
        assertEquals(5052, config.transferPort());
    }
}
