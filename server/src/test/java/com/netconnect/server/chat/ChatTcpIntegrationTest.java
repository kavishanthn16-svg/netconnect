package com.netconnect.server.chat;

import com.netconnect.common.contracts.ControlGateway;
import com.netconnect.common.contracts.Session;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.EnvelopeCodec;
import com.netconnect.common.protocol.EventType;
import com.netconnect.server.chat.ChatTestSupport.TestSession;
import com.netconnect.server.session.ConcurrentSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.netconnect.server.chat.ChatTestSupport.payload;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Five real TCP clients exchange 100 newline-delimited JSON messages through the chat module.
 * The line-per-connection harness stands in for the session/TLS owners' accept loop; it is test-only.
 */
@Timeout(60)
class ChatTcpIntegrationTest {
    private static final String[] NAMES = {"Alice", "Bobby", "Carol", "Daniel", "Erika"};

    private final ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
    private final Map<String, BufferedWriter> writers = new ConcurrentHashMap<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private ExecutorService pool;
    private ServerSocket serverSocket;
    private ChatEnvelopeHandler handler;

    @BeforeEach
    void startServer() throws IOException {
        pool = Executors.newCachedThreadPool();
        ControlGateway gateway = this::write;
        handler = new ChatEnvelopeHandler(new ChatMessageRouter(registry, gateway));
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(this::acceptLoop);
    }

    @AfterEach
    void stopServer() throws IOException {
        serverSocket.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        pool.shutdownNow();
    }

    private void write(Session session, Envelope envelope) {
        BufferedWriter writer = writers.get(session.sessionId());
        if (writer == null) {
            throw new IllegalStateException("no connection");
        }
        try {
            synchronized (writer) {
                writer.write(EnvelopeCodec.encode(envelope));
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void acceptLoop() {
        try {
            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                sockets.add(socket);
                pool.submit(() -> serve(socket));
            }
        } catch (IOException closed) {
            // server stopped
        }
    }

    private void serve(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String name = in.readLine();
            TestSession session = TestSession.of(name);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writers.put(session.sessionId(), out);
            assertTrue(registry.register(name, session).registered());
            synchronized (out) {
                out.write("READY\n");
                out.flush();
            }
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handler.handle(session, EnvelopeCodec.decode(line));
                } catch (IllegalArgumentException badLine) {
                    // the real read loop answers INVALID_REQUEST; irrelevant here
                }
            }
        } catch (IOException ignored) {
            // connection closed
        }
    }

    private final class Client {
        final String name;
        final Socket socket;
        final BufferedWriter out;
        final List<Envelope> received = new CopyOnWriteArrayList<>();

        Client(String name) throws IOException {
            this.name = name;
            socket = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
            sockets.add(socket);
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            send(name);
            assertEquals("READY", in.readLine());
            pool.submit(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        received.add(EnvelopeCodec.decode(line));
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
        }

        void send(String line) throws IOException {
            out.write(line);
            out.write('\n');
            out.flush();
        }

        void sendEnvelope(Envelope envelope) throws IOException {
            send(EnvelopeCodec.encode(envelope));
        }

        List<Envelope> deliveries() {
            return new ArrayList<>(received).stream().filter(e -> e.type() == EventType.CHAT_DELIVER).toList();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void fiveClientsExchangeOneHundredMessagesWithNoLeakDuplicationOrReordering() throws Exception {
        List<Client> clients = new ArrayList<>();
        for (String name : NAMES) {
            clients.add(new Client(name));
        }
        int perClient = 20;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clients.size());
        for (int c = 0; c < clients.size(); c++) {
            final int index = c;
            pool.submit(() -> {
                Client client = clients.get(index);
                String peer = NAMES[(index + 1) % NAMES.length];
                try {
                    go.await();
                    for (int i = 0; i < perClient; i++) {
                        String sessionId = "sid-" + client.name;
                        if (i % 2 == 0) {
                            client.sendEnvelope(Envelope.request(EventType.CHAT_GROUP_SEND, UUID.randomUUID(),
                                    sessionId, payload("text", client.name + "#" + i)));
                        } else {
                            client.sendEnvelope(Envelope.request(EventType.CHAT_PRIVATE_SEND, UUID.randomUUID(),
                                    sessionId, payload("toUser", peer, "text", client.name + "#" + i)));
                        }
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        // Each client: 50 group messages (all senders incl. self) + 10 private sent + 10 private received.
        int expected = 5 * (perClient / 2) + (perClient / 2) + (perClient / 2);
        awaitTrue(() -> clients.stream().allMatch(c -> c.deliveries().size() == expected), "all deliveries");
        Thread.sleep(200); // allow any duplicate to surface
        for (Client client : clients) {
            List<Envelope> deliveries = client.deliveries();
            assertEquals(expected, deliveries.size(), client.name + " delivery count (no loss/duplication)");
            assertEquals(deliveries.size(), deliveries.stream().map(e -> e.payload().get("messageId").asText()).distinct().count());
            for (Envelope e : deliveries) {
                if ("PRIVATE".equals(e.payload().get("scope").asText())) {
                    boolean mine = client.name.equals(e.payload().get("fromUser").asText())
                            || client.name.equals(e.payload().get("toUser").asText());
                    assertTrue(mine, client.name + " leaked a private message");
                }
            }
            // per-sender ordering over one TCP connection
            for (String sender : NAMES) {
                List<Integer> sequence = deliveries.stream()
                        .filter(e -> sender.equals(e.payload().get("fromUser").asText()))
                        .filter(e -> client.name.equals(e.payload().get("toUser") == null ? client.name
                                : e.payload().get("toUser").asText()) || sender.equals(client.name))
                        .map(e -> Integer.parseInt(e.payload().get("text").asText().split("#")[1]))
                        .toList();
                assertEquals(sequence.stream().sorted().toList(), sequence, client.name + " saw " + sender + " out of order");
            }
        }
        int totalGroup = clients.get(0).deliveries().stream().filter(e -> "GROUP".equals(e.payload().get("scope").asText())).toList().size();
        assertEquals(50, totalGroup);
    }

    @Test
    void thirdClientNeverSeesPrivateContent() throws Exception {
        Client alice = new Client("Alice");
        Client bobby = new Client("Bobby");
        Client carol = new Client("Carol");
        alice.sendEnvelope(Envelope.request(EventType.CHAT_PRIVATE_SEND, UUID.randomUUID(), "sid-Alice",
                payload("toUser", "Bobby", "text", "for Bobby only")));
        alice.sendEnvelope(Envelope.request(EventType.CHAT_GROUP_SEND, UUID.randomUUID(), "sid-Alice",
                payload("text", "for everyone")));
        awaitTrue(() -> carol.deliveries().size() == 1 && bobby.deliveries().size() == 2
                && alice.deliveries().size() == 2, "deliveries");
        Thread.sleep(150);
        assertEquals(1, carol.deliveries().size());
        assertEquals("for everyone", carol.deliveries().get(0).payload().get("text").asText());
    }

    @Test
    void malformedAndOversizedLinesDoNotKillTheConnection() throws Exception {
        Client alice = new Client("Alice");
        Client bobby = new Client("Bobby");
        alice.send("{not json");
        alice.send("x".repeat(20_000));
        alice.sendEnvelope(Envelope.request(EventType.CHAT_GROUP_SEND, UUID.randomUUID(), "sid-Alice",
                payload("text", "still works")));
        awaitTrue(() -> bobby.deliveries().size() == 1, "delivery after garbage");
    }

    @Test
    void offlineRecipientErrorTravelsBackOverTcp() throws Exception {
        Client alice = new Client("Alice");
        Envelope request = Envelope.request(EventType.CHAT_PRIVATE_SEND, UUID.randomUUID(), "sid-Alice",
                payload("toUser", "Zed", "text", "anyone?"));
        alice.sendEnvelope(request);
        awaitTrue(() -> alice.received.stream().anyMatch(e -> e.type() == EventType.ERROR), "error reply");
        Envelope error = alice.received.stream().filter(e -> e.type() == EventType.ERROR).findFirst().orElseThrow();
        assertEquals(request.requestId(), error.requestId());
        assertEquals("USER_OFFLINE", error.payload().get("code").asText());
    }
}
