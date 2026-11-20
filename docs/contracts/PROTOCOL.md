# NetConnect protocol baseline

## Control plane

TLS/TCP control traffic uses port `5050`, UTF-8 newline-delimited JSON, one object per line, and a maximum encoded line size of 16 KiB. The envelope fields are exactly `v`, `type`, `requestId`, `sessionId`, and `payload`. Version is `1`; event names are uppercase; every response echoes the request ID. Invalid messages produce `ERROR` and must not crash a session.

Frozen event names and payload fields are defined by SRS section 8.2. The `EventType` enum in `common` is the shared source for those names. Standard error codes are in `ErrorCode`.

## UDP hints

Presence uses port `5051` and the exact datagram form:

```text
PRESENCE_V1|<serverId>|<rosterRevision>|<unixMillis>
```

Hints are sent every five seconds and expire after fifteen seconds. UDP is non-authoritative and never grants authentication or transfer access.

## File plane

Accepted file bytes use NIO on port `5052`. The length-prefixed UTF-8 JSON handshake has a four-byte big-endian length and a maximum handshake size of 2048 bytes, followed by a one-byte acceptance status. The maximum file is 20 MiB. The v1 file channel is intentionally plaintext; it is not claimed to provide confidentiality.

## Validation limits

Display names are 3–24 characters, chat text is 1–2000 characters, file names must be sanitized by the transfer owner, and SHA-256 must be verified for accepted files.
