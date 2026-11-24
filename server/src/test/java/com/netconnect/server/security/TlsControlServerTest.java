package com.netconnect.server.security;

import com.netconnect.common.protocol.*;
import com.netconnect.server.session.ConcurrentSessionRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises control dispatch independently of certificate setup; client tests cover real TLS. */
class TlsControlServerTest {
    @Test void rejectsUnauthorizedRequestsAndCleansUpAuthenticatedSession() throws Exception {
        var registry = new ConcurrentSessionRegistry();
        var auth = new EnvironmentAuthService(registry, () -> "test-only-code");
        var payload = JsonNodeFactory.instance.objectNode();
        Envelope unauthorized = Envelope.request(EventType.CHAT_GROUP_SEND, UUID.randomUUID(), "spoofed", payload);
        Envelope denied = Envelope.request(EventType.AUTH_LOGIN, UUID.randomUUID(), null,
                payload.deepCopy().put("displayName", "Alice").put("accessCode", "wrong"));
        Envelope login = Envelope.request(EventType.AUTH_LOGIN, UUID.randomUUID(), null,
                payload.deepCopy().put("displayName", "Alice").put("accessCode", "test-only-code"));
        var socket = new MemorySocket(EnvelopeCodec.encode(unauthorized) + "\n"
                + EnvelopeCodec.encode(denied) + "\n" + EnvelopeCodec.encode(login) + "\n");
        TlsControlServer.serve(socket, auth, registry);
        var responses = socket.output.toString(StandardCharsets.UTF_8).lines().map(EnvelopeCodec::decode).toList();
        assertEquals(EventType.ERROR, responses.get(0).type());
        assertEquals("NOT_AUTHENTICATED", responses.get(0).payload().path("code").asText());
        assertEquals(EventType.AUTH_DENIED, responses.get(1).type());
        assertEquals("ACCESS_DENIED", responses.get(1).payload().path("code").asText());
        assertEquals(EventType.AUTH_OK, responses.get(2).type());
        assertEquals(login.requestId(), responses.get(2).requestId());
        assertNotNull(responses.get(2).sessionId());
        assertEquals(responses.get(2).sessionId(), responses.get(2).payload().path("sessionId").asText());
        assertEquals("Alice", responses.get(2).payload().path("displayName").asText());
        assertFalse(responses.get(1).payload().path("message").asText().isBlank());
        assertTrue(registry.listUsers().isEmpty());
        assertTrue(socket.closed);
        assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"}, socket.protocols);
    }

    @Test void handshakeFailureClosesSocketWithoutAuthenticating() {
        var socket = new MemorySocket("") {
            @Override public void startHandshake() throws IOException { throw new SSLHandshakeException("test failure"); }
        };
        var registry = new ConcurrentSessionRegistry();
        assertThrows(SSLHandshakeException.class, () -> TlsControlServer.serve(socket,
                (name, code) -> { fail("Authentication must not run"); return null; }, registry));
        assertTrue(socket.closed);
        assertTrue(registry.listUsers().isEmpty());
    }

    @Test void malformedMessageAfterLoginRemovesSession() {
        var registry = new ConcurrentSessionRegistry();
        var login = Envelope.request(EventType.AUTH_LOGIN, UUID.randomUUID(), null,
                JsonNodeFactory.instance.objectNode().put("displayName", "Alice").put("accessCode", "test-only-code"));
        var socket = new MemorySocket(EnvelopeCodec.encode(login) + "\ninvalid json\n");
        assertThrows(IOException.class, () -> TlsControlServer.serve(socket,
                new EnvironmentAuthService(registry, () -> "test-only-code"), registry));
        assertTrue(socket.closed);
        assertTrue(registry.listUsers().isEmpty());
    }

    @Test void repeatedDeniedLoginsAreBounded() throws Exception {
        var registry = new ConcurrentSessionRegistry();
        var login = Envelope.request(EventType.AUTH_LOGIN, UUID.randomUUID(), null,
                JsonNodeFactory.instance.objectNode().put("displayName", "Alice").put("accessCode", "wrong"));
        var socket = new MemorySocket((EnvelopeCodec.encode(login) + "\n").repeat(10));
        TlsControlServer.serve(socket, new EnvironmentAuthService(registry, () -> "test-code"), registry);
        assertEquals(5, socket.output.toString(StandardCharsets.UTF_8).lines().count());
        assertTrue(socket.closed);
        assertTrue(registry.listUsers().isEmpty());
    }

    private static class MemorySocket extends SSLSocket {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean closed;
        String[] protocols = {"TLSv1.3", "TLSv1.2", "TLSv1.1"};
        MemorySocket(String input) { this.input = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void close() { closed = true; }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void startHandshake() throws IOException { }
        @Override public String[] getEnabledProtocols() { return protocols.clone(); }
        @Override public void setEnabledProtocols(String[] values) { protocols = values.clone(); }
        @Override public String[] getSupportedProtocols() { return protocols.clone(); }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] values) { }
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void setUseClientMode(boolean value) { }
        @Override public boolean getUseClientMode() { return false; }
        @Override public void setNeedClientAuth(boolean value) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean value) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean value) { }
        @Override public boolean getEnableSessionCreation() { return true; }
    }
}
