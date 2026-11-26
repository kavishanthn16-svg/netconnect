package com.netconnect.server.security;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.server.session.BoundedSessionExecutor;
import com.netconnect.server.session.SessionExecutorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import com.netconnect.common.protocol.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLServerSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SecureControlApplicationTest {
    @TempDir Path directory;

    @Test @Timeout(60)
    void shutdownClosesActiveAndQueuedConnectionsAndTerminatesWorkers() throws Exception {
        int controlPort;
        int transferPort;
        try (var control = new ServerSocket(0); var transfer = new ServerSocket(0)) {
            controlPort = control.getLocalPort();
            transferPort = transfer.getLocalPort();
        }
        SSLContext context = testContext();
        var config = new NetConnectConfig("localhost", controlPort, 5051, transferPort,
                directory.resolve("staging"), directory.resolve("downloads"));
        var application = new SecureControlApplication(config, context, "test-only-code");
        var clients = new ArrayList<Socket>();
        var failure = new AtomicReference<Throwable>();
        Thread acceptor = new Thread(() -> {
            try { application.run(); } catch (Throwable error) { failure.set(error); }
        }, "test-production-acceptor");
        var workers = (BoundedSessionExecutor) field(application, "workers");
        var listener = (SSLServerSocket) field(application, "listener");
        Object transfers = field(application, "transfers");
        Object nio = field(transfers, "nioFileServer");
        Thread nioWorker = (Thread) field(nio, "workerThread");
        try {
            acceptor.start();
            int activeConnections = SessionExecutorConfig.defaults().maximumThreads();
            SSLSocket authenticated = (SSLSocket) context.getSocketFactory().createSocket();
            clients.add(authenticated);
            authenticated.connect(new java.net.InetSocketAddress("localhost", controlPort), 2000);
            authenticated.setSoTimeout(10000);
            authenticated.setEnabledProtocols(new String[]{"TLSv1.3"});
            var parameters = authenticated.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            authenticated.setSSLParameters(parameters);
            authenticated.startHandshake();
            var login = Envelope.request(EventType.AUTH_LOGIN, UUID.randomUUID(), null,
                    JsonNodeFactory.instance.objectNode().put("displayName", "ShutdownUser")
                            .put("accessCode", "test-only-code"));
            authenticated.getOutputStream().write((EnvelopeCodec.encode(login) + "\n").getBytes(StandardCharsets.UTF_8));
            authenticated.getOutputStream().flush();
            var reader = new BufferedReader(new InputStreamReader(authenticated.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(EventType.AUTH_OK, EnvelopeCodec.decode(reader.readLine()).type());
            authenticated.setSoTimeout(2000);
            Map<?, ?> peers = (Map<?, ?>) field(application, "peers");
            await(() -> peers.size() == 1);
            Thread writer = (Thread) field(peers.values().iterator().next(), "writer");
            // Other clients hold real TLS handshakes open; the final two sockets queue.
            for (int i = 1; i < activeConnections + 2; i++) {
                var client = new Socket();
                clients.add(client);
                client.connect(new java.net.InetSocketAddress("localhost", controlPort), 2000);
                client.setSoTimeout(2000);
            }
            await(() -> workers.activeTaskCount() == activeConnections && workers.queuedTaskCount() == 2);
            application.close(); // Actual production shutdown path; no process kill or forced test cleanup.
            acceptor.join(3000);
            nioWorker.join(3000);
            writer.join(3000);
            assertFalse(writer.isAlive(), "Authenticated peer writer must terminate");
            assertTrue(peers.isEmpty(), "Authenticated peers must be released");
            assertTrue(((com.netconnect.server.session.ConcurrentSessionRegistry) field(application, "registry")).listUsers().isEmpty());
            assertFalse(acceptor.isAlive(), "Production acceptor must stop");
            assertNull(failure.get(), "Listener closure must be treated as normal shutdown");
            assertTrue(listener.isClosed());
            assertTrue(workers.isTerminated(), "Active and queued control work must terminate");
            assertEquals(0, workers.activeTaskCount());
            assertEquals(0, workers.queuedTaskCount());
            assertFalse(nioWorker.isAlive(), "Production NIO worker must stop");
            for (Socket client : clients) {
                try {
                    int trailingBytes = 0;
                    while (client.getInputStream().read() != -1) {
                        // JSSE may send a TLS alert before closing the raw TCP connection.
                        assertTrue(++trailingBytes <= 2048, "Unexpected data after shutdown");
                    }
                }
                catch (SocketException reset) { /* A reset also proves the server closed the socket. */ }
            }
            application.close(); // Closing an already stopped application remains safe.
        } finally {
            application.close();
            for (Socket client : clients) client.close();
            acceptor.join(3000);
            nioWorker.join(3000);
        }
    }

    private SSLContext testContext() throws Exception {
        Path storePath = directory.resolve("shutdown-test.p12");
        Path log = directory.resolve("keytool.log");
        String password = "test-only-store-password";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "shutdown-test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-storetype", "PKCS12", "-keystore", storePath.toString(),
                "-storepass:env", "NETCONNECT_TEST_STORE_PASSWORD", "-noprompt");
        builder.environment().put("NETCONNECT_TEST_STORE_PASSWORD", password);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process keytool = builder.start();
        try {
            assertTrue(keytool.waitFor(30, TimeUnit.SECONDS), "Test certificate generation timed out");
            assertEquals(0, keytool.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception failure) { return failure.toString(); }
            });
        } finally {
            if (keytool.isAlive()) { keytool.destroyForcibly(); assertTrue(keytool.waitFor(5, TimeUnit.SECONDS)); }
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storePath)) { store.load(input, password.toCharArray()); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Production workers and queue did not reach the expected state");
    }
}
