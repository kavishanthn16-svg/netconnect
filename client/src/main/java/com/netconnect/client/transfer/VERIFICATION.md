# Verification and Acceptance Matrix - 234077D

## Acceptance Test Matrix (SRS Section 12)

| Test ID | Scenario | Expected Result | Implementation / Test Verification |
|---|---|---|---|
| **T06** | Upload 0 B, 1 KiB, 1 MiB, 20 MiB | Defined handling; progress reaches 100% without division by zero; verified SHA-256 match. | `NioFileTransferIntegrationTest.testEmptyFileTransfer()`, `testOneKiBFileTransfer()`, `testOneMiBFileTransfer()`, `testTwentyMiBFileTransfer()` |
| **T06 (Binary)** | Binary bytes containing 0x0A, 0x0D, 0x00, 0xFF | Raw binary stream preserved without corruption or line parsing; checksum verified. | `NioFileTransferIntegrationTest.testBinaryBytesWithNewlinesAndControlChars()` |
| **T07** | Unauthorized / invalid file ticket | Connection rejected with 1-byte `0` response and channel closed; no file read/written. | `NioFileTransferIntegrationTest.testInvalidTicketRejected()` |
| **T08** | Receiver rejects offer | Transfer terminates; `FILE_RESULT` with `TRANSFER_REJECTED` emitted; staging cleaned. | `NioFileTransferIntegrationTest.testRecipientRejection()` |
| **T08 (Disconnect)** | Abrupt client disconnect mid-transfer | Server detects EOF, frees channel, deletes staging file, and invalidates tickets. | `NioFileTransferIntegrationTest.testBrokenConnectionMidStream()` |
| **NFR-03 / NFR-04** | File name path traversal | Strips illegal paths (`../../evil.sh`), preserves safe file name. | `TransferFileNameSanitizerTest` |
| **NFR-03** | Oversized file (> 20 MiB) | Rejected before sending offer; bounded at 20 MiB. | `FileOfferTest` & `NioFileTransferIntegrationTest` |
