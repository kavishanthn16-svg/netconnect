package com.netconnect.common.contracts;

import com.netconnect.common.protocol.Envelope;

public interface ControlGateway {
    void send(Session session, Envelope envelope);
}
