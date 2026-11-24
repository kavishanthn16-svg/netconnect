package com.netconnect.client.security;

import com.netconnect.common.protocol.*;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Single reader and bounded writer for the socket transferred after verified AUTH_OK. */
public final class AuthenticatedControlConnection implements AutoCloseable {
    private final SSLSocket socket;
    private final String sessionId;
    private final BlockingQueue<Envelope> outbound = new ArrayBlockingQueue<>(64);
    private Thread reader;
    private Thread writer;
    private volatile boolean closed;

    public AuthenticatedControlConnection(SSLSocket socket, String sessionId) throws IOException {
        this.socket = socket;
        this.sessionId = sessionId;
        socket.setSoTimeout(0); // No production heartbeat/idle policy is defined in the current contracts.
    }
    public void start(Consumer<Envelope> received, Runnable disconnected) {
        writer = Thread.ofVirtual().name("netconnect-client-writer").unstarted(() -> {
            try {
                OutputStream out = socket.getOutputStream();
                while (!closed) {
                    Envelope request = outbound.take();
                    out.write((EnvelopeCodec.encode(request) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException failure) { close(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        reader = Thread.ofVirtual().name("netconnect-client-reader").unstarted(() -> {
            try {
                InputStream in = socket.getInputStream();
                while (!closed) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int next;
                    while ((next = in.read()) != '\n') {
                        if (next == -1) throw new EOFException();
                        if (bytes.size() >= Envelope.MAX_UTF8_BYTES) throw new IOException("Oversized control envelope");
                        bytes.write(next);
                    }
                    Envelope envelope = EnvelopeCodec.decode(bytes.toString(StandardCharsets.UTF_8));
                    if (!sessionId.equals(envelope.sessionId())) throw new IOException("Invalid session response");
                    received.accept(envelope);
                }
            } catch (IOException | RuntimeException failure) {
                // No server payloads or secrets are logged.
            } finally { close(); disconnected.run(); }
        });
        writer.start();
        reader.start();
    }
    public void send(EventType type, com.fasterxml.jackson.databind.node.ObjectNode payload) {
        if (closed || !outbound.offer(Envelope.request(type, java.util.UUID.randomUUID(), sessionId, payload))) close();
    }
    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
        if (writer != null) writer.interrupt();
        outbound.clear();
    }
}
