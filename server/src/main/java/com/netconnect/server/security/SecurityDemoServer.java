package com.netconnect.server.security;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.server.session.ConcurrentSessionRegistry;
import javax.net.ssl.*;
import java.io.*;
import java.net.SocketException;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.*;

/** Local-development authentication demo only; never substitutes for production bootstrap. */
public final class SecurityDemoServer {
    private SecurityDemoServer() { }
    public static void main(String[] args) throws Exception {
        required("NETCONNECT_ACCESS_CODE");
        char[] password = required("NETCONNECT_KEY_STORE_PASSWORD").toCharArray();
        SSLContext context = SSLContext.getInstance("TLS");
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(Path.of(required("NETCONNECT_KEY_STORE")))) {
                store.load(input, password);
            }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, password);
            context.init(managers.getKeyManagers(), null, null);
        } finally { Arrays.fill(password, '\0'); }
        var registry = new ConcurrentSessionRegistry();
        var auth = new EnvironmentAuthService(registry);
        Set<SSLSocket> active = ConcurrentHashMap.newKeySet();
        var workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        try (workers;
             SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(
                     NetConnectConfig.load().controlPort(), 16, java.net.InetAddress.getLoopbackAddress())) {
            listener.setEnabledProtocols(Boolean.parseBoolean(System.getenv("NETCONNECT_ALLOW_TLS12"))
                    ? new String[]{"TLSv1.3", "TLSv1.2"} : new String[]{"TLSv1.3"});
            Thread hook = new Thread(() -> shutdownServer(listener, active, workers), "security-demo-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("NetConnect local TLS authentication demo listening on " + listener.getLocalSocketAddress());
            try {
                while (!listener.isClosed()) {
                    SSLSocket socket;
                    try {
                        socket = (SSLSocket) listener.accept();
                    } catch (SocketException closed) {
                        if (listener.isClosed()) break;
                        throw closed;
                    }
                    active.add(socket);
                    try {
                        workers.execute(() -> {
                            try { TlsControlServer.serve(socket, auth, registry); }
                            catch (IOException | RuntimeException failure) {
                                System.err.println("Demo connection ended or rejected (credentials omitted).");
                            } finally { active.remove(socket); }
                        });
                    } catch (RejectedExecutionException busy) {
                        active.remove(socket);
                        socket.close();
                    }
                }
            } finally {
                shutdownServer(listener, active, workers);
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException shuttingDown) { }
            }
        }
    }

    static void shutdownServer(SSLServerSocket listener, Set<SSLSocket> active, ThreadPoolExecutor workers) {
        try { listener.close(); }
        catch (IOException failure) { System.err.println("Unable to close the demo listener."); }
        workers.shutdown();
        for (SSLSocket socket : active) {
            try { socket.close(); }
            catch (IOException failure) { System.err.println("Unable to close an active demo connection."); }
        }
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                if (!workers.awaitTermination(5, TimeUnit.SECONDS))
                    System.err.println("Demo worker threads did not terminate after forced shutdown.");
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Configure " + name);
        return value;
    }
}
