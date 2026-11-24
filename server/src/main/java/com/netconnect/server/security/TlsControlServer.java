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
        SSLServerSocket socket = (SSLServerSocket) SSLServerSocketFactory.getDefault()
                .createServerSocket(config.controlPort());
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
        String authenticatedId = null;
        String authenticatedName = null;
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
                        payload.put("code", result.code());
                        payload.put("message", switch (result.code()) {
                            case "NAME_TAKEN" -> "That display name is already in use.";
                            case "INVALID_REQUEST" -> "Invalid display name or credentials.";
                            default -> "Access denied. Check your access code.";
                        });
                    }
                    if (authenticatedId != null) socket.setSoTimeout(0);
                } else {
                    responseType = EventType.ERROR;
                    boolean valid = AuthenticationState.permits(authenticatedId, authenticatedName, request, registry);
                    payload.put("code", valid ? "INVALID_REQUEST" : "NOT_AUTHENTICATED");
                }
                Envelope response = Envelope.request(responseType, request.requestId(), authenticatedId, payload);
                output.write((EnvelopeCodec.encode(response) + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } finally {
            if (authenticatedId != null) registry.remove(authenticatedId);
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
