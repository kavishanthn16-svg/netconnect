package com.netconnect.server.bootstrap;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.server.security.SecureControlApplication;

/** Production entry point: one TLS control listener and the existing NIO file service. */
public final class NetConnectServer {
    private NetConnectServer() { }
    public static void main(String[] args) throws Exception {
        NetConnectConfig config = NetConnectConfig.load();
        try (var application = new SecureControlApplication(config)) {
            Thread hook = new Thread(application::close, "netconnect-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                System.out.println("NetConnect TLS control listening on port " + config.controlPort());
                application.run();
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException shuttingDown) { }
            }
        }
    }
}
