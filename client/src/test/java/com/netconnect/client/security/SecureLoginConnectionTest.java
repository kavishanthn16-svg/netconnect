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
            assertThrows(IOException.class, client::detachAuthenticatedSocket);
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

    @Test void productionServerRetainsTlsSessionAndAuthorizesFileTransfer() throws Exception {
        Path serverClasses = Path.of("..", "server", "target", "classes").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(serverClasses), "Build the reactor so production server classes are available");
        int controlPort = unusedPort(), transferPort = unusedPort();
        String secret = UUID.randomUUID().toString();
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path") + File.pathSeparator + serverClasses,
                "com.netconnect.server.bootstrap.NetConnectServer");
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("production-server.log").toFile());
        var env = builder.environment();
        env.put("NETCONNECT_ACCESS_CODE", secret);
        env.put("NETCONNECT_KEY_STORE", directory.resolve("server.p12").toString());
        env.put("NETCONNECT_KEY_STORE_PASSWORD", password);
        env.put("NETCONNECT_CONTROL_PORT", Integer.toString(controlPort));
        env.put("NETCONNECT_TRANSFER_PORT", Integer.toString(transferPort));
        env.put("NETCONNECT_FILE_STAGING_DIR", directory.resolve("production-staging").toString());
        env.put("NETCONNECT_ALLOW_TLS12", "false");
        Process production = builder.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                assertTrue(production.isAlive(), "Production startup failed: " + Files.readString(directory.resolve("production-server.log")));
                try (var probe = new java.net.Socket()) {
                    probe.connect(new java.net.InetSocketAddress("localhost", controlPort), 200);
                    break;
                } catch (IOException pending) {
                    if (System.nanoTime() >= deadline) throw pending;
                    Thread.sleep(50);
                }
            }
            var d = NetConnectConfig.defaults();
            var config = new NetConnectConfig("localhost", controlPort, d.udpPort(), transferPort,
                    directory, directory);
            try (var alice = new SecureLoginConnection(directory.resolve("trusted.p12"), password.toCharArray(), false);
                 var bob = new SecureLoginConnection(directory.resolve("trusted.p12"), password.toCharArray(), false);
                 var denied = new SecureLoginConnection(directory.resolve("trusted.p12"), password.toCharArray(), false)) {
                assertEquals(EventType.AUTH_DENIED, denied.login(config, "Alice", "wrong").type());
                Envelope a = alice.login(config, "Alice", secret);
                assertEquals(EventType.AUTH_OK, a.type());
                assertEquals("NAME_TAKEN", denied.login(config, "ALICE", secret).payload().path("code").asText());
                Envelope b = bob.login(config, "Bobby", secret);
                try (SSLSocket aSocket = alice.detachAuthenticatedSocket(); SSLSocket bSocket = bob.detachAuthenticatedSocket()) {
                    aSocket.setSoTimeout(5000); bSocket.setSoTimeout(5000);
                    var ar = new BufferedReader(new InputStreamReader(aSocket.getInputStream(), StandardCharsets.UTF_8));
                    var br = new BufferedReader(new InputStreamReader(bSocket.getInputStream(), StandardCharsets.UTF_8));
                    UUID spoof = UUID.randomUUID();
                    write(aSocket, Envelope.request(EventType.FILE_OFFER, spoof, b.sessionId(), JsonNodeFactory.instance.objectNode()));
                    Envelope rejected = await(ar, EventType.ERROR);
                    assertEquals(spoof, rejected.requestId());
                    assertEquals("NOT_AUTHENTICATED", rejected.payload().path("code").asText());
                    UUID rosterId = UUID.randomUUID();
                    write(aSocket, Envelope.request(EventType.USER_ROSTER, rosterId, a.sessionId(), JsonNodeFactory.instance.objectNode()));
                    Envelope roster;
                    do { roster = await(ar, EventType.USER_ROSTER); } while (!rosterId.equals(roster.requestId()));
                    assertEquals(2, roster.payload().path("users").size());
                    UUID transferId = UUID.randomUUID();
                    byte[] contents = "Authenticated production transfer".getBytes(StandardCharsets.UTF_8);
                    String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(contents));
                    Path source = Files.write(directory.resolve("production-source.txt"), contents);
                    UUID offerId = UUID.randomUUID();
                    write(aSocket, Envelope.request(EventType.FILE_OFFER, offerId, a.sessionId(), JsonNodeFactory.instance.objectNode()
                            .put("transferId", transferId.toString()).put("toUser", "Bobby").put("fileName", "production.txt")
                            .put("sizeBytes", contents.length).put("sha256", hash)));
                    assertEquals(offerId, await(br, EventType.FILE_OFFERED).requestId());
                    UUID decisionId = UUID.randomUUID();
                    write(bSocket, Envelope.request(EventType.FILE_DECISION, decisionId, b.sessionId(), JsonNodeFactory.instance.objectNode()
                            .put("transferId", transferId.toString()).put("accept", true)));
                    Envelope send = await(ar, EventType.FILE_READY), receive = await(br, EventType.FILE_READY);
                    assertEquals(decisionId, send.requestId());
                    assertEquals(decisionId, receive.requestId());
                    assertEquals(0, fileHandshake(transferPort, transferId, send.payload().path("ticket").asText(), "RECEIVE"));
                    com.netconnect.client.transfer.NioFileSender.sendFile("localhost", transferPort, transferId,
                            send.payload().path("ticket").asText(), source, (done, total) -> { });
                    Path destination = directory.resolve("production-received.txt");
                    com.netconnect.client.transfer.NioFileReceiver.receiveFile("localhost", transferPort, transferId,
                            receive.payload().path("ticket").asText(), contents.length, hash, destination, (done, total) -> { });
                    assertArrayEquals(contents, Files.readAllBytes(destination));
                    assertEquals("SUCCESS", await(ar, EventType.FILE_RESULT).payload().path("status").asText());
                    assertEquals(0, fileHandshake(transferPort, transferId, send.payload().path("ticket").asText(), "SEND"));
                    write(aSocket, Envelope.request(EventType.DISCONNECT, UUID.randomUUID(), a.sessionId(), JsonNodeFactory.instance.objectNode()));
                    while (ar.readLine() != null) { }
                }
                long retryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                Envelope reused;
                do {
                    reused = denied.login(config, "Alice", secret);
                    if (reused.type() == EventType.AUTH_OK) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < retryDeadline);
                assertEquals(EventType.AUTH_OK, reused.type());
                var events = new LinkedBlockingQueue<Envelope>();
                var disconnected = new CountDownLatch(1);
                try (var transport = new AuthenticatedControlConnection(denied.detachAuthenticatedSocket(), reused.sessionId())) {
                    transport.start(events::add, disconnected::countDown);
                    transport.send(EventType.FILE_OFFER, JsonNodeFactory.instance.objectNode());
                    Envelope event;
                    do {
                        event = events.poll(5, TimeUnit.SECONDS);
                        assertNotNull(event, "Retained shell transport must receive the server response");
                    } while (event.type() == EventType.USER_ROSTER);
                    assertEquals(EventType.ERROR, event.type());
                    assertEquals("INVALID_REQUEST", event.payload().path("code").asText());
                }
                assertTrue(disconnected.await(5, TimeUnit.SECONDS), "Closing shell transport must terminate its reader");
            }
        } finally {
            production.destroy();
            if (!production.waitFor(5, TimeUnit.SECONDS)) { production.destroyForcibly(); assertTrue(production.waitFor(5, TimeUnit.SECONDS)); }
        }
    }

    private static int unusedPort() throws IOException {
        try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
    }
    private static void write(SSLSocket socket, Envelope request) throws IOException {
        socket.getOutputStream().write((EnvelopeCodec.encode(request) + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private static Envelope await(BufferedReader reader, EventType type) throws IOException {
        for (int i = 0; i < 30; i++) {
            String line = reader.readLine();
            if (line == null) throw new EOFException();
            Envelope response = EnvelopeCodec.decode(line);
            if (response.type() == type) return response;
            assertNotEquals(EventType.ERROR, response.type(), "Unexpected production error");
        }
        throw new IOException("Expected control event not received");
    }
    private static int fileHandshake(int port, UUID transfer, String ticket, String role) throws IOException {
        try (var socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            byte[] json = JsonNodeFactory.instance.objectNode().put("transferId", transfer.toString())
                    .put("ticket", ticket).put("role", role).toString().getBytes(StandardCharsets.UTF_8);
            var out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(json.length); out.write(json); out.flush();
            return socket.getInputStream().read();
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
                if (reply == EventType.AUTH_OK) {
                    try (SSLSocket handedOff = client.detachAuthenticatedSocket()) {
                        assertEquals(listener.getLocalPort(), handedOff.getPort());
                        assertEquals(protocol, handedOff.getSession().getProtocol());
                        client.close();
                        assertFalse(handedOff.isClosed(), "Login owner must not close a handed-off socket");
                        assertThrows(IOException.class, client::detachAuthenticatedSocket);
                    }
                } else assertThrows(IOException.class, client::detachAuthenticatedSocket);
            } else if (trusted) assertThrows(IOException.class, () -> client.login(config, "Alice", "test-only-code"));
            else assertThrows(SSLException.class, () -> client.login(config, "Alice", "test-only-code"));
            server.get(10, TimeUnit.SECONDS);
        }
    }
}
