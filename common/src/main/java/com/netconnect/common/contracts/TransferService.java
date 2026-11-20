package com.netconnect.common.contracts;

public interface TransferService {
    void offer(Session sender, FileOffer offer);

    void decide(Session recipient, String transferId, boolean accepted);
}
