package com.netconnect.client.transfer;

/**
 * Callback listener for streaming transfer progress.
 */
@FunctionalInterface
public interface TransferProgressListener {
    void onProgress(long bytesTransferred, long totalBytes);
}
