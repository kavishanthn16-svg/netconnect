package com.netconnect.server.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netconnect.common.contracts.ControlGateway;
import com.netconnect.common.contracts.Session;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.EventType;
import com.netconnect.server.session.ConcurrentSessionRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class ChatTestSupport {
    private ChatTestSupport() {
    }

    record TestSession(String sessionId, String displayName) implements Session {
        static TestSession of(String name) {
            return new TestSession("sid-" + name, name);
        }
    }

    /** Records every envelope per session id; optionally fails for chosen sessions. */
    static final class RecordingGateway implements ControlGateway {
        private final Map<String, List<Envelope>> sent = new ConcurrentHashMap<>();
        volatile Predicate<Session> failFor = s -> false;

        @Override
        public void send(Session session, Envelope envelope) {
            if (failFor.test(session)) {
                throw new IllegalStateException("socket closed");
            }
            sent.computeIfAbsent(session.sessionId(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(envelope);
        }

        List<Envelope> all(Session session) {
            List<Envelope> list = sent.getOrDefault(session.sessionId(), List.of());
            synchronized (list) {
                return new ArrayList<>(list);
            }
        }

        List<Envelope> ofType(Session session, EventType type) {
            return all(session).stream().filter(e -> e.type() == type).toList();
        }
    }

    static TestSession login(ConcurrentSessionRegistry registry, String name) {
        TestSession session = TestSession.of(name);
        if (!registry.register(name, session).registered()) {
            throw new IllegalStateException("could not register " + name);
        }
        return session;
    }

    static Envelope request(EventType type, String sessionId, ObjectNode payload) {
        return Envelope.request(type, UUID.randomUUID(), sessionId, payload);
    }

    static ObjectNode payload(String... keyValues) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return node;
    }
}
