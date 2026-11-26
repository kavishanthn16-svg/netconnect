package com.netconnect.server.security;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.contracts.*;
import com.netconnect.common.protocol.*;
import com.netconnect.server.session.*;
import com.netconnect.server.transfer.NioTransferService;
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Production TLS transport adapting the existing session and transfer contracts. */
public final class SecureControlApplication implements AutoCloseable, ControlGateway, TlsControlServer.Handler {
    private final ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
    private final BoundedSessionExecutor workers = new BoundedSessionExecutor(workerConfig());
    private final SessionLifecycleManager lifecycle = new SessionLifecycleManager(registry, workers, Duration.ofSeconds(5));
    private final Set<SSLSocket> accepted = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final SSLServerSocket listener;
    private final NioTransferService transfers;
    private final AuthService auth;
    private final ThreadLocal<UUID> requestId = new ThreadLocal<>();
    private final com.netconnect.server.chat.ChatEnvelopeHandler chat = new com.netconnect.server.chat.ChatEnvelopeHandler(
            new com.netconnect.server.chat.ChatMessageRouter(registry, this));
    private volatile boolean closed;

    private static SessionExecutorConfig workerConfig() {
        var defaults = SessionExecutorConfig.defaults();
        // A long-lived control reader occupies its worker for the connection lifetime.
        return new SessionExecutorConfig(defaults.maximumThreads(), defaults.maximumThreads(),
                defaults.queueCapacity(), defaults.threadNamePrefix());
    }

    public SecureControlApplication(NetConnectConfig config) throws IOException, GeneralSecurityException {
        this(config, serverContext(), null);
    }

    SecureControlApplication(NetConnectConfig config, SSLContext context, String testCode) throws IOException {
        auth = testCode == null ? new EnvironmentAuthService(registry) : new EnvironmentAuthService(registry, () -> testCode);
        listener = TlsControlServer.bind(config, context, Boolean.parseBoolean(System.getenv("NETCONNECT_ALLOW_TLS12")));
        NioTransferService service = null;
        try {
            service = new NioTransferService(config, registry, this, java.time.Clock.systemUTC(), config.fileStagingDirectory());
            service.start();
            transfers = service;
        } catch (IOException | RuntimeException failure) {
            listener.close();
            if (service != null) service.close();
            workers.close();
            throw failure;
        }
    }

    public void run() throws IOException {
        while (!closed) {
            SSLSocket socket;
            try { socket = (SSLSocket) listener.accept(); }
            catch (java.net.SocketException failure) { if (closed) return; throw failure; }
            accepted.add(socket);
            if (closed) { socket.close(); accepted.remove(socket); break; }
            if (!workers.tryExecute(() -> {
                try { TlsControlServer.serve(socket, auth, registry, this); }
                catch (IOException | RuntimeException failure) {
                    System.err.println("Control connection closed or rejected (credentials omitted).");
                } finally { accepted.remove(socket); }
            })) { accepted.remove(socket); socket.close(); }
        }
    }

    @Override public void connected(Session session, SSLSocket socket) throws IOException {
        Peer peer = new Peer(socket);
        peers.put(session.sessionId(), peer);
        peer.writer.start();
        roster();
    }

    @Override public void request(Session session, Envelope request) {
        var p = request.payload();
        requestId.set(request.requestId());
        try {
            switch (request.type()) {
                case USER_ROSTER -> sendRoster(session, request.requestId());
                case CHAT_GROUP_SEND, CHAT_PRIVATE_SEND -> chat.handle(session, request);
                case FILE_OFFER -> {
                    if (!p.path("sizeBytes").isIntegralNumber() || !p.path("sizeBytes").canConvertToLong()
                            || !text(p, "sha256").matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
                    transfers.offer(session, new FileOffer(UUID.fromString(text(p, "transferId")), text(p, "toUser"),
                            text(p, "fileName"), p.path("sizeBytes").longValue(), text(p, "sha256")));
                }
                case FILE_DECISION -> {
                    if (!p.path("accept").isBoolean()) throw new IllegalArgumentException();
                    transfers.decide(session, text(p, "transferId"), p.path("accept").booleanValue());
                }
                default -> error(session, request, "INVALID_REQUEST");
            }
        } catch (IllegalArgumentException invalid) { error(session, request, "INVALID_REQUEST"); }
        finally { requestId.remove(); }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode p, String field) {
        if (!p.path(field).isTextual() || p.path(field).asText().isBlank()) throw new IllegalArgumentException();
        return p.path(field).asText();
    }
    private void error(Session session, Envelope request, String code) {
        send(session, Envelope.request(EventType.ERROR, request.requestId(), session.sessionId(),
                JsonNodeFactory.instance.objectNode().put("code", code)));
    }
    @Override public void send(Session session, Envelope envelope) {
        Peer peer = peers.get(session.sessionId());
        if (requestId.get() != null) envelope = Envelope.request(envelope.type(), requestId.get(), envelope.sessionId(), envelope.payload());
        if (peer != null && !peer.outbound.offer(envelope)) peer.close();
    }
    private void sendRoster(Session session, UUID requestId) {
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put("revision", registry.rosterRevision());
        var users = payload.putArray("users");
        registry.listUsers().forEach(users::add);
        send(session, Envelope.request(EventType.USER_ROSTER, requestId, session.sessionId(), payload));
    }
    private void roster() {
        registry.listUsers().forEach(name -> registry.getByDisplayName(name)
                .ifPresent(session -> sendRoster(session, UUID.randomUUID())));
    }
    @Override public void disconnected(String sessionId) {
        Peer peer = peers.remove(sessionId);
        if (peer != null) peer.close();
        transfers.handleDisconnect(sessionId);
        roster();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        accepted.forEach(socket -> { try { socket.close(); } catch (IOException ignored) { } });
        peers.values().forEach(Peer::close);
        try { lifecycle.close(); }
        catch (InterruptedException interrupted) { workers.close(); Thread.currentThread().interrupt(); }
        transfers.close();
        peers.clear();
    }
    private static SSLContext serverContext() throws IOException, GeneralSecurityException {
        required("NETCONNECT_ACCESS_CODE");
        char[] password = required("NETCONNECT_KEY_STORE_PASSWORD").toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(required("NETCONNECT_KEY_STORE")))) { store.load(in, password); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            return context;
        } finally { Arrays.fill(password, '\0'); }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Configure " + key);
        return value;
    }
    private static final class Peer {
        final SSLSocket socket;
        final BlockingQueue<Envelope> outbound = new ArrayBlockingQueue<>(64);
        final Thread writer;
        Peer(SSLSocket socket) {
            this.socket = socket;
            writer = Thread.ofVirtual().name("netconnect-control-writer").unstarted(() -> {
                try {
                    OutputStream output = socket.getOutputStream();
                    while (!socket.isClosed()) {
                        Envelope envelope = outbound.take();
                        synchronized (output) {
                            output.write((EnvelopeCodec.encode(envelope) + "\n").getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        }
                    }
                } catch (IOException failure) { close(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }
        void close() {
            try { socket.close(); } catch (IOException ignored) { }
            writer.interrupt();
            outbound.clear();
        }
    }
}
