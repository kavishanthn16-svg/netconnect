package com.netconnect.client.security;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.protocol.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.UUID;

/** Retains the authenticated control socket until explicitly closed. Call off the FX thread. */
public final class SecureLoginConnection implements AutoCloseable {
    private final SSLContext context;
    private final boolean allowTls12;
    private int responseTimeoutMillis = 10000;
    private SSLSocket socket;
    private Envelope authentication;

    /** Uses the JVM's configured trust anchors and retains hostname verification. */
    public SecureLoginConnection(boolean allowTls12) throws GeneralSecurityException {
        context = SSLContext.getDefault();
        this.allowTls12 = allowTls12;
    }

    public SecureLoginConnection(Path trustStore, char[] password, boolean allowTls12)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trustStore)) { store.load(in, password); }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        this.allowTls12 = allowTls12;
    }

    SecureLoginConnection(Path trustStore, char[] password, int responseTimeoutMillis)
            throws GeneralSecurityException, IOException {
        this(trustStore, password, false);
        if (responseTimeoutMillis < 1) throw new IllegalArgumentException("Timeout must be positive");
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    public synchronized Envelope login(NetConnectConfig config, String name, String accessCode) throws IOException {
        close();
        if (name == null || name.isBlank() || name.length() < 3 || name.length() > 24
                || name.codePoints().anyMatch(Character::isISOControl)
                || accessCode == null || accessCode.isBlank())
            throw new IOException("Enter a display name of 3-24 characters and a nonblank access code");
        try {
            socket = (SSLSocket) context.getSocketFactory().createSocket();
            socket.setEnabledProtocols(allowTls12 ? new String[]{"TLSv1.3", "TLSv1.2"} : new String[]{"TLSv1.3"});
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.connect(new InetSocketAddress(config.serverHost(), config.controlPort()), 5000);
            socket.setSoTimeout(responseTimeoutMillis);
            socket.startHandshake();
            UUID id = UUID.randomUUID();
            var payload = JsonNodeFactory.instance.objectNode().put("displayName", name).put("accessCode", accessCode);
            Envelope request = Envelope.request(EventType.AUTH_LOGIN, id, null, payload);
            socket.getOutputStream().write((EnvelopeCodec.encode(request) + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) != '\n') {
                if (value == -1) throw new EOFException("Server closed the connection");
                if (bytes.size() >= Envelope.MAX_UTF8_BYTES) throw new IOException("Oversized response");
                bytes.write(value);
            }
            Envelope response;
            try { response = EnvelopeCodec.decode(bytes.toString(StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid authentication response", invalid); }
            if (!id.equals(response.requestId()) || (response.type() != EventType.AUTH_OK
                    && response.type() != EventType.AUTH_DENIED)
                    || (response.type() == EventType.AUTH_OK && (response.sessionId() == null || response.sessionId().isBlank()
                    || !response.sessionId().equals(response.payload().path("sessionId").asText())
                    || !name.equals(response.payload().path("displayName").asText())))
                    || (response.type() == EventType.AUTH_DENIED && (response.sessionId() != null
                    || !response.payload().path("code").isTextual() || response.payload().path("code").asText().isBlank())))
                throw new IOException("Unexpected authentication response");
            if (response.type() == EventType.AUTH_DENIED) close();
            else authentication = response;
            return response;
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /**
     * Transfers the existing verified TLS socket to the shell after AUTH_OK.
     * The caller becomes its sole owner and must close it, run reads off the FX thread,
     * and apply the application's liveness/read-timeout policy. No new connection is opened.
     * The login timeout remains in force until the new owner configures its policy.
     */
    public synchronized SSLSocket detachAuthenticatedSocket() throws IOException {
        if (socket == null || socket.isClosed() || authentication == null)
            throw new IOException("An authenticated connection is required");
        SSLSocket authenticated = socket;
        socket = null;
        authentication = null;
        return authenticated;
    }

    @Override public synchronized void close() throws IOException {
        authentication = null;
        if (socket != null) { try { socket.close(); } finally { socket = null; } }
    }
}
