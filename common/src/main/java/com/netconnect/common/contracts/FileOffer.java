package com.netconnect.common.contracts;

import java.util.Objects;
import java.util.UUID;

public record FileOffer(UUID transferId, String toUser, String fileName, long sizeBytes, String sha256) {
    public FileOffer {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(toUser, "toUser");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256, "sha256");
        if (sizeBytes < 0 || sizeBytes > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("File size must be between 0 and 20 MiB");
        }
    }
}
