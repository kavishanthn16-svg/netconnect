# Contract decisions requiring team approval

The SRS v1.0 freezes the names, fields, ports, and conceptual service methods. The following Java declarations are the compile-safe foundation interpretation and must be explicitly approved in the foundation PR before feature implementation.

## Concrete declarations

| Contract | Java declaration | Owner of implementation |
| --- | --- | --- |
| `SessionRegistry` | `SessionResult register(String, Session)`; `Optional<Session> getByDisplayName(String)`; `List<String> listUsers()`; `void remove(String sessionId)` | 234106T, `server/session/**` |
| `MessageRouter` | `void sendGroup(Session, String)`; `void sendPrivate(Session, String, String)` | 234229X, `server/chat/**` |
| `PresenceService` | `void start()`; `void registerEndpoint(Session, int)`; `void stop()` | 234012C, `server/discovery/**` |
| `TransferService` | `void offer(Session, FileOffer)`; `void decide(Session, String, boolean)` | 234077D, `server/transfer/**` |
| `AuthService` | `AuthResult authenticate(String displayName, String accessCode)` | 234042R, `server/security/**` |
| `ControlGateway` | `void send(Session, Envelope)` | 234042R/TLS integration with server services |

## Shared types

- `Session` exposes only `sessionId()` and `displayName()`; transport operations stay behind `ControlGateway`.
- `SessionResult` reports registration success, a bounded code, and the registered session.
- `AuthResult` reports authentication success, a bounded code, session ID, and display name.
- `FileOffer` carries the frozen `transferId`, `toUser`, `fileName`, `sizeBytes`, and `sha256` fields and rejects sizes above 20 MiB.
- `Envelope` uses version `1`, uppercase `EventType`, UUID `requestId`, nullable `sessionId`, and an object payload.

## Decisions still requiring approval

1. Whether `SessionResult.code` and `AuthResult.code` should be replaced by the shared `ErrorCode` enum for all future implementations.
2. Whether `FileOffer.transferId` should remain a `UUID` at service boundaries while JSON remains a UUID string.
3. Whether future implementations should use checked `IOException` declarations or convert transport failures into protocol `ERROR` responses at the gateway boundary.
4. The concrete callback/queue mechanism used by `ControlGateway` implementations; the foundation intentionally does not choose one.
5. The optional `udpPort` field in `AUTH_LOGIN`, which is permitted by the SRS and must not become a new event type.

No implementation should silently change these declarations. Resolve disagreements through a CONTRACT-CHANGE issue and PR.
