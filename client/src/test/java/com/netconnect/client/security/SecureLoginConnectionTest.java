package com.netconnect.client.security;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.protocol.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SecureLoginConnectionTest {
    @TempDir static Path directory;
    private static String password;
    private static SSLContext serverContext;

    @BeforeAll static void certificates() throws Exception {
        password = UUID.randomUUID().toString();
        keytool("-genkeypair", "-alias", "demo", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", directory.resolve("server.p12").toString());
        keytool("-exportcert", "-alias", "demo", "-keystore", directory.resolve("server.p12").toString(),
                "-file", directory.resolve("server.cer").toString());
        keytool("-importcert", "-noprompt", "-alias", "demo", "-storetype", "PKCS12",
                "-keystore", directory.resolve("trusted.p12").toString(),
                "-file", directory.resolve("server.cer").toString());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(directory.resolve("server.p12"))) {
            keys.load(input, password.toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, password.toCharArray());
        try (OutputStream out = Files.newOutputStream(directory.resolve("untrusted.p12"))) {
            empty.store(out, password.toCharArray());
        }
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toString());
        if (!Files.exists(Path.of(command.getFirst())))
            command.set(0, Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        command.addAll(List.of("-storepass:env", "NETCONNECT_TEST_STORE_PASSWORD"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve("keytool-output.txt").toFile());
        builder.environment().put("NETCONNECT_TEST_STORE_PASSWORD", password);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Temporary certificate generation timed out");
        }
        assertEquals(0, process.exitValue(), "Temporary certificate generation failed");
    }

    @Test void trustedCertificateAllowsLogin() throws Exception { exchange("trusted.p12", "localhost", true, EventType.AUTH_OK); }
    @Test void authenticationDenialIsReturned() throws Exception { exchange("trusted.p12", "localhost", true, EventType.AUTH_DENIED); }
    @Test void untrustedCertificateIsRejected() throws Exception { exchange("untrusted.p12", "localhost", false, EventType.AUTH_OK); }
    @Test void incorrectHostnameIsRejected() throws Exception { exchange("trusted.p12", "127.0.0.1", false, EventType.AUTH_OK); }
    @Test void configuredTls12AllowsLogin() throws Exception {
        exchange("trusted.p12", "localhost", true, EventType.AUTH_OK, "TLSv1.2", true);
    }
    @Test void tls12RequiresExplicitOptIn() throws Exception {
        exchange("trusted.p12", "localhost", false, EventType.AUTH_OK, "TLSv1.2", false);
    }
    @Test void unexpectedResponseIsRejectedAndClosed() throws Exception {
        exchange("trusted.p12", "localhost", true, EventType.ERROR);
    }
    @Test void malformedResponseIsRejectedAndClosed() throws Exception {
        exchange("trusted.p12", "localhost", true, null);
    }
    @Test void invalidCredentialsFailBeforeConnecting() throws Exception {
        try (var client = new SecureLoginConnection(directory.resolve("trusted.p12"), password.toCharArray(), false)) {
            assertThrows(IOException.class, () -> client.login(NetConnectConfig.defaults(), "ab", "code"));
            assertThrows(IOException.class, () -> client.login(NetConnectConfig.defaults(), "Alice", " "));
        }
    }

    @Test void timeoutClosesSocketAndSameClientCanRetry() throws Exception {
        try (SSLServerSocket listener = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0);
             ExecutorService worker = Executors.newSingleThreadExecutor();
             var client = new SecureLoginConnection(directory.resolve("trusted.p12"), password.toCharArray(), 1500)) {
            listener.setSoTimeout(5000);
            Future<?> server = worker.submit(() -> {
                try {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try (SSLSocket socket = (SSLSocket) listener.accept()) {
                            socket.setSoTimeout(5000);
                            socket.startHandshake();
                            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            var request = EnvelopeCodec.decode(reader.readLine());
                            if (attempt == 1) {
                                var response = Envelope.request(EventType.AUTH_DENIED, request.requestId(), null,
                                        JsonNodeFactory.instance.objectNode().put("code", "ACCESS_DENIED"));
                                socket.getOutputStream().write((EnvelopeCodec.encode(response) + "\n").getBytes(StandardCharsets.UTF_8));
                                socket.getOutputStream().flush();
                            }
                            assertEquals(-1, socket.getInputStream().read());
                        }
                    }
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            var d = NetConnectConfig.defaults();
            var config = new NetConnectConfig("localhost", listener.getLocalPort(), d.udpPort(), d.transferPort(),
                    d.fileStagingDirectory(), d.clientDownloadDirectory());
            assertThrows(java.net.SocketTimeoutException.class, () -> client.login(config, "Alice", "test-code"));
            assertEquals(EventType.AUTH_DENIED, client.login(config, "Alice", "test-code").type());
            server.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void unavailableServerFailsCleanly() throws Exception {
        int port;
        try (var unused = new java.net.ServerSocket(0)) { port = unused.getLocalPort(); }
        var d = NetConnectConfig.defaults();
        var config = new NetConnectConfig("localhost", port, d.udpPort(), d.transferPort(),
                d.fileStagingDirectory(), d.clientDownloadDirectory());
        try (var client = new SecureLoginConnection(false)) {
            assertThrows(IOException.class, () -> client.login(config, "Alice", "test-code"));
            client.close();
        }
    }

    private void exchange(String store, String host, boolean trusted, EventType reply) throws Exception {
        exchange(store, host, trusted, reply, "TLSv1.3", false);
    }

    private void exchange(String store, String host, boolean trusted, EventType reply,
                          String protocol, boolean allowTls12) throws Exception {
        try (SSLServerSocket listener = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0);
             ExecutorService worker = Executors.newSingleThreadExecutor();
             SecureLoginConnection client = new SecureLoginConnection(directory.resolve(store), password.toCharArray(), allowTls12)) {
            listener.setSoTimeout(5000);
            listener.setEnabledProtocols(new String[]{protocol});
            Future<?> server = worker.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    if (!trusted) return;
                    assertEquals(protocol, socket.getSession().getProtocol());
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    Envelope request = EnvelopeCodec.decode(reader.readLine());
                    assertEquals(EventType.AUTH_LOGIN, request.type());
                    var payload = JsonNodeFactory.instance.objectNode();
                    if (reply == EventType.AUTH_DENIED) payload.put("code", "ACCESS_DENIED");
                    Envelope response = Envelope.request(reply == null ? EventType.AUTH_OK : reply, request.requestId(),
                            reply == EventType.AUTH_OK ? UUID.randomUUID().toString() : null, payload);
                    if (reply == EventType.AUTH_OK) payload.put("sessionId", response.sessionId()).put("displayName", "Alice");
                    socket.getOutputStream().write(((reply == null ? "invalid json" : EnvelopeCodec.encode(response)) + "\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    assertEquals(-1, socket.getInputStream().read(), "Client must close its connection");
                } catch (IOException failure) {
                    if (trusted) throw new UncheckedIOException(failure);
                }
            });
            NetConnectConfig defaults = NetConnectConfig.defaults();
            NetConnectConfig config = new NetConnectConfig(host, listener.getLocalPort(), defaults.udpPort(),
                    defaults.transferPort(), defaults.fileStagingDirectory(), defaults.clientDownloadDirectory());
            if (trusted && (reply == EventType.AUTH_OK || reply == EventType.AUTH_DENIED)) {
                assertEquals(reply, client.login(config, "Alice", "test-only-code").type());
                if (reply == EventType.AUTH_OK) client.close();
            } else if (trusted) assertThrows(IOException.class, () -> client.login(config, "Alice", "test-only-code"));
            else assertThrows(SSLException.class, () -> client.login(config, "Alice", "test-only-code"));
            server.get(10, TimeUnit.SECONDS);
        }
    }
}
