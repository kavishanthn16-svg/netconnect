# File Transfer Integration Guide

This guide details how other team members integrate with `com.netconnect.server.transfer` and `com.netconnect.client.transfer`.

## 1. Bootstrap and Session Lifecycle Integration (234106T)

### In Server Bootstrap:
```java
// Instantiate NioTransferService
NioTransferService transferService = new NioTransferService(
    config,              // NetConnectConfig
    sessionRegistry,     // SessionRegistry
    controlGateway       // ControlGateway
);
transferService.start(); // Starts NIO ServerSocketChannel on port 5052

// On server shutdown hook:
transferService.close();
```

### In Session Disconnect Lifecycle:
When a client session disconnects (graceful or abrupt):
```java
transferService.handleDisconnect(session.sessionId());
```
This cancels any in-flight transfers involving this user, deletes staging files, invalidates pending tickets, and sends error results to peers.

## 2. JavaFX Shell Integration (234106T)

In `NetConnectClient.java` (JavaFX Application thread):
```java
FileTransferPane fileTransferPane = new FileTransferPane();
Tab transferTab = new Tab("File Transfers", fileTransferPane);
transferTab.setClosable(false);
// Mount into navigation TabPane
navigation.getTabs().set(2, transferTab);
```

### Outgoing Control Wiring:
```java
fileTransferPane.setOnOfferRequested(offer -> {
    // Send FILE_OFFER envelope via TLS control gateway
    ObjectNode payload = mapper.createObjectNode()
        .put("transferId", offer.transferId().toString())
        .put("toUser", offer.toUser())
        .put("fileName", offer.fileName())
        .put("sizeBytes", offer.sizeBytes())
        .put("sha256", offer.sha256());
    controlConnection.send(Envelope.request(EventType.FILE_OFFER, UUID.randomUUID(), sessionId, payload));
});

fileTransferPane.setOnDecisionRequested((transferId, accept) -> {
    // Send FILE_DECISION envelope via TLS control gateway
    ObjectNode payload = mapper.createObjectNode()
        .put("transferId", transferId)
        .put("accept", accept);
    controlConnection.send(Envelope.request(EventType.FILE_DECISION, UUID.randomUUID(), sessionId, payload));
});
```

## 3. TLS Control Dispatcher Integration (234042R / 234106T)

When control envelopes arrive from the server:

- **On `FILE_OFFERED`**:
  ```java
  UUID transferId = UUID.fromString(payload.get("transferId").asText());
  String fromUser = payload.get("fromUser").asText();
  String fileName = payload.get("fileName").asText();
  long sizeBytes = payload.get("sizeBytes").asLong();
  String sha256 = payload.get("sha256").asText();
  fileTransferPane.onFileOffered(transferId, fromUser, fileName, sizeBytes, sha256);
  ```

- **On `FILE_READY`**:
  ```java
  UUID transferId = UUID.fromString(payload.get("transferId").asText());
  String ticket = payload.get("ticket").asText();
  String host = payload.get("host").asText();
  int port = payload.get("port").asInt();
  String role = payload.get("role").asText();
  long expiresAt = payload.get("expiresAt").asLong();
  fileTransferPane.onFileReady(transferId, ticket, host, port, role, expiresAt);
  ```

- **On `FILE_RESULT`**:
  ```java
  UUID transferId = UUID.fromString(payload.get("transferId").asText());
  String status = payload.get("status").asText();
  String code = payload.get("code").asText();
  String message = payload.get("message").asText();
  fileTransferPane.onFileResult(transferId, status, code, message);
  ```

- **On `USER_ROSTER`**:
  ```java
  fileTransferPane.updateOnlineUsers(userList);
  ```

- **On TLS Disconnect / Reconnect**:
  ```java
  fileTransferPane.resetForConnection();
  ```
