package com.netconnect.server.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netconnect.common.contracts.ControlGateway;
import com.netconnect.common.contracts.MessageRouter;
import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionRegistry;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.ErrorCode;
import com.netconnect.common.protocol.EventType;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes group and private chat messages (SRS 17).
 *
 * <p>Only the frozen {@link SessionRegistry} and {@link ControlGateway} contracts are used. Group
 * messages go to every registered session once (including the sender); private messages go to the
 * sender and the named recipient only. Message text is never logged (NFR-06).
 */
public final class ChatMessageRouter implements MessageRouter {
    public static final int MAX_TEXT_LENGTH = 2000;
    public static final String SCOPE_GROUP = "GROUP";
    public static final String SCOPE_PRIVATE = "PRIVATE";

    private static final Logger LOGGER = Logger.getLogger(ChatMessageRouter.class.getName());

    private final SessionRegistry registry;
    private final ControlGateway gateway;
    private final Clock clock;

    public ChatMessageRouter(SessionRegistry registry, ControlGateway gateway) {
        this(registry, gateway, Clock.systemUTC());
    }

    public ChatMessageRouter(SessionRegistry registry, ControlGateway gateway, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void sendGroup(Session sender, String text) {
        sendGroup(sender, text, UUID.randomUUID());
    }

    @Override
    public void sendPrivate(Session sender, String toUser, String text) {
        sendPrivate(sender, toUser, text, UUID.randomUUID());
    }

    /** Group send; {@code requestId} is echoed on any ERROR reply. */
    public void sendGroup(Session sender, String text, UUID requestId) {
        if (!precheck(sender, text, requestId)) {
            return;
        }
        ObjectNode payload = deliverPayload(sender, null, SCOPE_GROUP, text);
        for (String name : registry.listUsers()) {
            registry.getByDisplayName(name).ifPresent(target -> deliver(target, payload));
        }
    }

    /** Private send; {@code requestId} is echoed on any ERROR reply. */
    public void sendPrivate(Session sender, String toUser, String text, UUID requestId) {
        if (!precheck(sender, text, requestId)) {
            return;
        }
        if (toUser == null || toUser.isBlank()) {
            sendError(sender, requestId, ErrorCode.INVALID_REQUEST, "Recipient is required");
            return;
        }
        Session recipient = registry.getByDisplayName(toUser).orElse(null);
        if (recipient == null) {
            sendError(sender, requestId, ErrorCode.USER_OFFLINE, "Recipient is not online");
            return;
        }
        ObjectNode payload = deliverPayload(sender, recipient.displayName(), SCOPE_PRIVATE, text);
        deliver(sender, payload);
        if (!recipient.sessionId().equals(sender.sessionId())) {
            deliver(recipient, payload);
        }
    }

    /** True when the session is the live, registered session for its display name. */
    public boolean isAuthenticated(Session session) {
        if (session == null || session.sessionId() == null || session.displayName() == null) {
            return false;
        }
        return registry.getByDisplayName(session.displayName())
                .map(registered -> session.sessionId().equals(registered.sessionId()))
                .orElse(false);
    }

    /** Sends a protocol ERROR that echoes the request id of the failing request. */
    public void sendError(Session session, UUID requestId, ErrorCode code, String message) {
        if (session == null) {
            return;
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", code.name());
        payload.put("message", message);
        safeSend(session, new Envelope(Envelope.VERSION, EventType.ERROR, requestId,
                session.sessionId(), payload));
        LOGGER.log(Level.FINE, "chat request {0} rejected: {1}", new Object[]{requestId, code});
    }

    private boolean precheck(Session sender, String text, UUID requestId) {
        if (!isAuthenticated(sender)) {
            sendError(sender, requestId, ErrorCode.NOT_AUTHENTICATED, "Login is required");
            return false;
        }
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            sendError(sender, requestId, ErrorCode.INVALID_REQUEST,
                    "Message must be 1-" + MAX_TEXT_LENGTH + " characters");
            return false;
        }
        return true;
    }

    private ObjectNode deliverPayload(Session sender, String toUser, String scope, String text) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("fromUser", sender.displayName());
        if (toUser != null) {
            payload.put("toUser", toUser);
        }
        payload.put("scope", scope);
        payload.put("text", text);
        payload.put("sentAt", clock.instant().toString());
        return payload;
    }

    private void deliver(Session target, ObjectNode payload) {
        safeSend(target, new Envelope(Envelope.VERSION, EventType.CHAT_DELIVER, UUID.randomUUID(),
                target.sessionId(), payload.deepCopy()));
    }

    /** One failing recipient must never stop delivery to the others. */
    private void safeSend(Session target, Envelope envelope) {
        try {
            gateway.send(target, envelope);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "chat delivery of {0} failed for session {1}: {2}",
                    new Object[]{envelope.type(), target.sessionId(), failure.getClass().getSimpleName()});
        }
    }
}
