# NIO File Transfer Handoff - 234077D

## Snapshot and ownership

- **Owner**: 234077D
- **Feature**: NIO File Transfer (Java NIO port 5052, control plane file events)
- **Branch**: `feature/234077d-file-transfer`
- **Target PR branch**: `development`
- **Strictly owned paths**:
  - `server/src/main/java/com/netconnect/server/transfer/**`
  - `server/src/test/java/com/netconnect/server/transfer/**`
  - `client/src/main/java/com/netconnect/client/transfer/**`
  - `client/src/test/java/com/netconnect/client/transfer/**`

No common DTOs, root `pom.xml`, or files owned by other members were modified.

## Delivered Components

### Server (`server/transfer`)
1. `TransferFileNameSanitizer.java`: Strips directory separators, prevents path traversal (`..`), Windows reserved characters, and bounds file names to 255 chars (NFR-03, NFR-04).
2. `TransferTicket.java`: Record binding `transferId`, `sessionId`, `role` ("SEND" or "RECEIVE"), and 60-second expiration.
3. `TicketRegistry.java`: Thread-safe registry for single-use ticket issuance, validation, atomic consumption, and session/transfer cleanup.
4. `NioHandshake.java`: Codec for 4-byte big-endian length prefix framing + UTF-8 JSON handshake `{"v":1,"transferId":"...","ticket":"...","role":"SEND"|"RECEIVE"}`.
5. `ActiveTransfer.java`: State machine tracking transfer lifecycle:
   `OFFERED -> ACCEPTED -> READY -> SENDING -> STAGED -> RECEIVING -> VERIFIED -> COMPLETED`
   (or `REJECTED`, `FAILED`, `EXPIRED`).
6. `NioFileServer.java`: Non-blocking NIO server on port 5052 using `ServerSocketChannel`, `SocketChannel.configureBlocking(false)`, `Selector`, and `ByteBuffer`. Handles partial reads/writes, backpressure, 20-MiB size bounding, server staging with SHA-256 verification, and delivery with final 1/0 ack.
7. `NioTransferService.java`: Implements `com.netconnect.common.contracts.TransferService`. Coordinates `FILE_OFFER` and `FILE_DECISION` requests, issues tickets, dispatches `FILE_OFFERED`, `FILE_READY`, and `FILE_RESULT` via `ControlGateway`.

### Client (`client/transfer`)
1. `TransferProgressListener.java`: Progress listener interface `(bytesTransferred, totalBytes) -> void`.
2. `NioFileSender.java`: Connects via NIO to server port 5052, sends framed handshake, receives 1-byte accept status, streams raw file bytes, and reports progress.
3. `NioFileReceiver.java`: Connects via NIO to server port 5052, sends framed handshake, receives raw bytes into target destination, calculates SHA-256 on the fly, verifies checksum, and sends final 1-byte ack (`1` on match, `0` on mismatch).
4. `FileTransferPane.java`: JavaFX desktop component providing recipient selection, file browsing with background SHA-256 calculation, send offer button, incoming offer review with save location chooser and file overwrite confirmation, progress bar with bytes/percentage, and activity log.

## Explicit Architectural Limitation (SRS NFR-07)
- **File data on NIO channel is plaintext (NOT TLS encrypted) in v1**: The educational focus is demonstrating raw Java NIO non-blocking socket channels, selectors, byte buffers, framing, and cryptographic digest verification. In accordance with SRS Section 0 and NFR-07, use only non-sensitive test files on a trusted classroom LAN.

## 2-Minute Live Evaluation Script (SRS Section 21)
- **Time 0:00 - 0:30**: Select recipient and choose a sample PDF or image (< 20 MiB). Show automatic background SHA-256 computation in the UI without freezing JavaFX. Click "Send File Offer".
- **Time 0:30 - 0:50**: On recipient client, observe incoming offer card showing sender, file name, size, and SHA-256. Click "Accept", choose target destination path, and confirm save location.
- **Time 0:50 - 1:20**: Observe real-time progress bar updating bytes transferred and percentage simultaneously on both clients over NIO port 5052.
- **Time 1:20 - 1:40**: Point out completed status and matching verified SHA-256 digest on receiver side.
- **Time 1:40 - 2:00**: Explain Java NIO networking classes used (`ServerSocketChannel`, `SocketChannel.configureBlocking(false)`, `Selector`, `ByteBuffer`), framing, and one-time ticket security.
