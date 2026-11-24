package com.netconnect.server.security;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.contracts.*;
import com.netconnect.common.protocol.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Socket primitives for the server owner to run on its existing session executor. */
public final class TlsControlServer {
    private TlsControlServer() { }

    public static SSLServerSocket bind(NetConnectConfig config, boolean allowTls12) throws IOException {
        return bind(config, (SSLServerSocketFactory) SSLServerSocketFactory.getDefault(), allowTls12);
    }

    public static SSLServerSocket bind(NetConnectConfig config, SSLContext context, boolean allowTls12) throws IOException {
        return bind(config, context.getServerSocketFactory(), allowTls12);
    }

    private static SSLServerSocket bind(NetConnectConfig config, SSLServerSocketFactory factory, boolean allowTls12) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(config.controlPort());
        try {
            socket.setEnabledProtocols(allowTls12 ? new String[]{"TLSv1.3", "TLSv1.2"} : new String[]{"TLSv1.3"});
            return socket;
        } catch (RuntimeException failure) {
            try { socket.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Serves authentication only; authenticated feature dispatch remains with its owners. */
    public static void serve(SSLSocket socket, AuthService auth, SessionRegistry registry) throws IOException {
        serve(socket, auth, registry, null);
    }

    /** Security-owned lifecycle adapter; shared service contracts remain unchanged. */
    public interface Handler {
        void connected(Session session, SSLSocket socket) throws IOException;
        void request(Session session, Envelope request) throws IOException;
        void disconnected(String sessionId);
    }

    public static void serve(SSLSocket socket, AuthService auth, SessionRegistry registry, Handler handler) throws IOException {
        String authenticatedId = null;
        String authenticatedName = null;
        int deniedAttempts = 0;
        try (socket) {
            // Preserve the listener's TLS 1.2 opt-in while excluding legacy protocols.
            socket.setEnabledProtocols(java.util.Arrays.stream(socket.getEnabledProtocols())
                    .filter(protocol -> protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2"))
                    .toArray(String[]::new));
            socket.setSoTimeout(15000);
            socket.startHandshake();
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            String line;
            while ((line = readLine(input)) != null) {
                // Unparseable envelopes have no reliable request ID to echo: close the connection.
                Envelope request;
                try { request = EnvelopeCodec.decode(line); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid control envelope"); }
                var payload = JsonNodeFactory.instance.objectNode();
                EventType responseType;
                if (request.type() == EventType.AUTH_LOGIN && authenticatedId == null) {
                    var name = request.payload().get("displayName");
                    var code = request.payload().get("accessCode");
                    AuthResult result = auth.authenticate(name != null && name.isTextual() ? name.textValue() : null,
                            code != null && code.isTextual() ? code.textValue() : null);
                    responseType = result.authenticated() ? EventType.AUTH_OK : EventType.AUTH_DENIED;
                    if (result.authenticated()) {
                        authenticatedId = result.sessionId();
                        authenticatedName = result.displayName();
                        payload.put("sessionId", authenticatedId).put("displayName", authenticatedName);
                    } else {
                        deniedAttempts++;
                        payload.put("code", result.code());
                        payload.put("message", switch (result.code()) {
                            case "NAME_TAKEN" -> "That display name is already in use.";
                            case "INVALID_REQUEST" -> "Invalid display name or credentials.";
                            default -> "Access denied. Check your access code.";
                        });
                    }
                    if (authenticatedId != null) socket.setSoTimeout(0);
                } else {
                    boolean valid = AuthenticationState.permits(authenticatedId, authenticatedName, request, registry);
                    if (valid && request.type() == EventType.DISCONNECT) break;
                    if (valid && handler != null && request.type() != EventType.AUTH_LOGIN) {
                        handler.request(registry.getByDisplayName(authenticatedName).orElseThrow(), request);
                        continue;
                    }
                    responseType = EventType.ERROR;
                    payload.put("code", valid ? "INVALID_REQUEST" : "NOT_AUTHENTICATED");
                }
                Envelope response = Envelope.request(responseType, request.requestId(), authenticatedId, payload);
                synchronized (output) {
                    output.write((EnvelopeCodec.encode(response) + "\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                if (responseType == EventType.AUTH_OK && handler != null)
                    handler.connected(registry.getByDisplayName(authenticatedName).orElseThrow(), socket);
                if (deniedAttempts >= 5) break;
            }
        } finally {
            if (authenticatedId != null) {
                registry.remove(authenticatedId);
                if (handler != null) handler.disconnected(authenticatedId);
            }
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (bytes.size() >= Envelope.MAX_UTF8_BYTES) throw new IOException("Control message exceeds 16 KiB");
            bytes.write(value);
        }
        if (bytes.size() != 0) throw new EOFException("Incomplete control envelope");
        return null;
    }
}
