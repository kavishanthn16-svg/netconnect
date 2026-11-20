# netconnect
NetConnect is a JavaFX-based LAN communication system built using Java Network Programming, featuring real-time messaging, UDP device discovery, multithreading, NIO file transfer, and secure communication.

## Foundation baseline

This repository follows the proposed NetConnect SRS v1.0. The current foundation branch provides the Maven reactor, frozen shared protocol contracts, configuration defaults, a headless server launcher, and a JavaFX shell. Feature implementations are intentionally deferred until the foundation PR and shared contracts are reviewed by all members.

### Requirements

- Java 21
- Maven 3.9+
- JavaFX 21 runtime support for the client

### Build and test

From PowerShell:

```powershell
mvn -B clean verify
```

### Run the server foundation shell

```powershell
mvn -pl server -am package
java -cp "server\target\classes;common\target\classes" com.netconnect.server.bootstrap.NetConnectServer
```

The server shell logs startup and waits for a clean shutdown with Ctrl+C. It does not yet bind network sockets.

### Run the JavaFX client shell

```powershell
mvn -B -pl common -am install
mvn -f client\pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run
```

The client shell contains status, chat, online-user, file-transfer, and login placeholders. Blocking network work must remain outside the JavaFX Application Thread.

### Fixed defaults

| Purpose | Default |
| --- | ---: |
| TLS/TCP control | 5050 |
| UDP presence | 5051 |
| NIO file transfer | 5052 |
| Control protocol | UTF-8 newline-delimited JSON, max 16 KiB |
| Maximum file | 20 MiB |

Override non-secret values with the variables in `.env.example` or matching Java system properties. Never commit access codes, certificates, keys, or real environment files.

### Collaboration

`main` is stable, `development` is the integration branch, and foundation/feature work is submitted through pull requests. The foundation branch is `feature/234106t-foundation`; later concurrency work must start only after the foundation PR is approved and merged.

See [docs/architecture/FOUNDATION.md](docs/architecture/FOUNDATION.md), [docs/contracts/PROTOCOL.md](docs/contracts/PROTOCOL.md), and [docs/contracts/CONTRACT_DECISIONS.md](docs/contracts/CONTRACT_DECISIONS.md).
