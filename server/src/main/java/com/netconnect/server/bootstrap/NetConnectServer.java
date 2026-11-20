package com.netconnect.server.bootstrap;

import com.netconnect.common.config.NetConnectConfig;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Headless foundation launcher. Network services are intentionally extension points at this stage. */
public final class NetConnectServer {
    private static final Logger LOGGER = Logger.getLogger(NetConnectServer.class.getName());

    private NetConnectServer() {
    }

    public static void main(String[] args) throws InterruptedException {
        NetConnectConfig config = NetConnectConfig.load();
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("NetConnect server shutting down");
            shutdown.countDown();
        }, "netconnect-shutdown"));

        LOGGER.log(Level.INFO, "NetConnect server foundation started: control={0}, udp={1}, transfer={2}",
                new Object[]{config.controlPort(), config.udpPort(), config.transferPort()});
        LOGGER.info("Network service implementations are extension points for the approved member modules");
        shutdown.await();
    }
}
