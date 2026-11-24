# NetConnect security - member 234042R

## Architecture and production integration

The production entry point is `com.netconnect.server.bootstrap.NetConnectServer`.
It owns one `SecureControlApplication`, one TLS control listener (5050 by default),
the existing bounded session executor/lifecycle manager, and `NioTransferService`.
There is no plaintext control listener or plaintext fallback. The standalone
loopback-only `SecurityDemoServer` remains available for independent demonstrations.

`TlsControlServer` handles the TLS handshake and bounded newline-delimited JSON
control envelopes. TLS 1.3 is enabled by default; TLS 1.2 requires explicit opt-in.
Client certificates are not required; clients authenticate with the shared access
code. Server certificate chains and HTTPS hostname identity are verified by JSSE.
No trust-all manager is used. The client connect timeout is five seconds; handshake
and authentication response timeout is ten seconds. The server handshake and
unauthenticated socket read timeout is fifteen seconds. Five denied login attempts
close a connection. Envelopes are bounded at 16 KiB. Malformed envelopes without a
reliable request ID close the connection; invalid authenticated feature payloads
return a correlated ERROR. Credentials and transfer tokens are omitted from
security diagnostics. There is no global or per-IP login rate limiter.

`EnvironmentAuthService` reads NETCONNECT_ACCESS_CODE, compares UTF-8 bytes with
MessageDigest.isEqual, validates 3-24 character names without control characters,
and atomically reserves case-insensitive names in ConcurrentSessionRegistry.
Missing/blank configuration fails closed. UUID session IDs are connection-bound.

Authentication sequence:

1. The client verifies the server certificate and hostname over TLS.
2. AUTH_LOGIN carries displayName and accessCode, with a fresh requestId.
3. AUTH_OK returns sessionId/displayName; AUTH_DENIED returns a bounded code and
   safe message. The response echoes the requestId.
4. Only requests whose sessionId matches both the connection and active registry
   entry reach feature dispatch. Another user's ID cannot impersonate that user.
5. FILE_OFFER and FILE_DECISION dispatch through the existing TransferService.
   USER_ROSTER comes from the authoritative session registry. Unsupported chat
   requests return ERROR because this checkout has no MessageRouter implementation.
6. EOF, DISCONNECT, malformed input, and transport failure remove the session,
   abort associated transfers, revoke tickets, and make the display name reusable.

Each authenticated peer has one virtual writer and a bounded queue of 64 envelopes.
A full output queue closes that peer. Long-lived control readers use the existing
bounded worker policy, with core workers equal to its maximum (at most 16), avoiding
two long-lived connections preventing workers from servicing further clients.
Shutdown closes accepted and queued sockets before waiting for executor termination.
An authenticated idle/heartbeat timeout is not defined by current shared contracts;
idle authenticated connections remain open until disconnect or transport failure.

## File-transfer ticket lifecycle

Production NioTransferService now issues tokens through TransferTicketService.
A ticket contains 256 random bits, binds one active session, transferId, and SEND
or RECEIVE role, and expires at exactly 60 seconds. TicketRegistry adapts the
existing NIO handshake to security validation without changing its byte protocol.
The transfer participant for the specified role must match the ticket's session,
and that session must still exist in the authoritative registry. Validation and
consumption are atomic. Invalid role/transfer attempts preserve the valid ticket;
replays and disconnected-session tickets fail. Decisions and disconnect handling
are serialized with offer registration. Security ticket storage is capped at
10,000 entries and expired entries are purged on use.

The file handshake carries a bearer ticket, not a separately authenticated TLS
session. Possession of that token authorizes its transfer role. This is the approved
v1 protocol and is not proof of the connecting machine's identity. Control traffic
is encrypted; NIO file handshakes and file bytes on port 5052 are PLAINTEXT. SHA-256
checks integrity against the metadata delivered over TLS; it does not encrypt bytes.
Do not use v1 file transport when file confidentiality is required.

## JavaFX integration

The existing NetConnectClient shell now starts with SecureLoginPane. It retains
its tabs and mounts the existing OnlineUsersPane and FileTransferPane after login.
SecureLoginConnection.detachAuthenticatedSocket transfers the already authenticated
socket once; it never reconnects. AuthenticatedControlConnection owns that socket,
its bounded writer, and its sole reader. Networking runs outside the FX thread.
Incoming events are dispatched onto the FX thread. Disconnect returns to a fresh
login form; application shutdown closes transport and the transfer pane executor.
The standalone SecureLoginLauncher remains available.

The login form retains the dark blue theme, rounded controls, visible focus,
masked access-code field, accessible input names, progress state, disabled repeated
submissions, certificate-specific errors, denial/duplicate feedback, retry, and
password clearing. Successful status appears only after real TLS and AUTH_OK.
The shell login uses a scroll container for smaller windows. No dashboard or chat
implementation was recreated.

## Environment

| Variable | Purpose |
| --- | --- |
| NETCONNECT_ACCESS_CODE | Required server shared secret; absent/blank fails closed |
| NETCONNECT_KEY_STORE | Required server PKCS12 keystore path |
| NETCONNECT_KEY_STORE_PASSWORD | Required keystore/private-key password |
| NETCONNECT_TRUST_STORE | Optional client PKCS12 truststore; otherwise JVM trust anchors |
| NETCONNECT_TRUST_STORE_PASSWORD | Client truststore password |
| NETCONNECT_CONTROL_PORT | Control listener, default 5050 |
| NETCONNECT_SERVER_HOST | Client destination and advertised transfer host, default localhost |
| NETCONNECT_TRANSFER_PORT | NIO file listener, default 5052 |
| NETCONNECT_FILE_STAGING_DIR | Production staging directory, default staging |
| NETCONNECT_CLIENT_DOWNLOAD_DIR | Shared client configuration, default downloads; transfer pane uses its destination chooser |
| NETCONNECT_ALLOW_TLS12 | Explicit true enables TLS 1.2 alongside TLS 1.3 |
| NETCONNECT_DEMO_STORE_PASSWORD | Development certificate setup only |

For LAN use, set the server's advertised host to a reachable hostname and issue a
certificate whose SAN includes that hostname. The localhost certificate below is
for development only. Verify certificate fingerprints out of band before trusting
a LAN server. Environment variables remain sensitive process memory; do not print
or commit them. Java Strings/environment credentials cannot be reliably wiped.

## Windows PowerShell setup and launch

From the repository root, use Java 21 and Maven. If mvn is missing from PATH on this
workstation, use its existing installation:

```powershell
Set-Location D:\TeamProject\netConnect
$env:Path = "C:\Users\ASUS\.m2\wrapper\dists\apache-maven-3.9.12-bin\5nmfsn99br87k5d4ajlekdq10k\apache-maven-3.9.12\bin;$env:Path"
mvn -B clean verify
```

Generate development credentials outside the repository in server terminal A.
Use a new directory if the alias already exists. Passwords use prompts and keytool
environment options, not command-line secrets:

```powershell
$demo = Join-Path $env:TEMP 'netconnect-local-demo'
New-Item -ItemType Directory -Force $demo | Out-Null
$env:NETCONNECT_DEMO_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Development store password (at least 6 characters)' -AsSecureString)).Password
keytool -genkeypair -alias netconnect -keyalg RSA -keysize 3072 -validity 7 -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 -keystore "$demo/server.p12" -storepass:env NETCONNECT_DEMO_STORE_PASSWORD
keytool -exportcert -alias netconnect -keystore "$demo/server.p12" -storepass:env NETCONNECT_DEMO_STORE_PASSWORD -file "$demo/server.cer"
keytool -importcert -noprompt -alias netconnect -file "$demo/server.cer" -storetype PKCS12 -keystore "$demo/trusted.p12" -storepass:env NETCONNECT_DEMO_STORE_PASSWORD
$env:NETCONNECT_KEY_STORE = "$demo/server.p12"
$env:NETCONNECT_KEY_STORE_PASSWORD = $env:NETCONNECT_DEMO_STORE_PASSWORD
$env:NETCONNECT_ACCESS_CODE = [System.Net.NetworkCredential]::new('', (Read-Host 'Choose a local access code' -AsSecureString)).Password
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./server/src/main/resources/com/netconnect/server/security/security-demo.ps1 -Mode ProductionServer
```

In client terminal B:

```powershell
Set-Location D:\TeamProject\netConnect
$demo = Join-Path $env:TEMP 'netconnect-local-demo'
$env:NETCONNECT_TRUST_STORE = "$demo/trusted.p12"
$env:NETCONNECT_TRUST_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Same development store password' -AsSecureString)).Password
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./server/src/main/resources/com/netconnect/server/security/security-demo.ps1 -Mode ProductionClient
```

The helper uses built classes plus the existing Maven-cache Jackson 2.18.3 and
Windows JavaFX 21.0.6 jars. It installs no dependencies and changes no POMs.
Launch on an unlocked interactive desktop. This workstation disables direct script invocation, so the commands use a process-only ExecutionPolicy override; they do not change machine or user policy. The script's Server and Client modes
still launch the standalone authentication demo. Do not run the demo server and
production server on the same control port. Ctrl+C stops the server.

## Testing and two-minute demonstration

```powershell
mvn -B -pl server -am '-Dtest=SecurityTest,TlsControlServerTest,TransferTicketServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -pl client -am '-Dtest=SecureLoginConnectionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B clean verify
```

The client security suite includes a real production-entry-point subprocess test;
run the reactor build first so server/target/classes is available. It generates
one-day test certificates and random credentials in JUnit temporary directories,
starts the actual NetConnectServer, authenticates two clients, rejects wrong codes
and duplicate names, rejects session impersonation, requests an authoritative roster,
approves a file offer, transfers bytes with SHA-256 verification, rejects wrong-role
and replayed tickets, and verifies disconnect/name reuse. All subprocesses are
terminated in finally blocks. Other tests cover trust, hostname mismatch, TLS 1.2
opt-in, timeouts/retry, malformed envelopes, concurrent duplicate names, ticket
expiry/binding/atomic consumption, and demo shutdown. No test is disabled.

Prepare certificates/build beforehand. Two-minute presentation:

1. 0:00-0:20 Start ProductionServer and ProductionClient using A/B above.
2. 0:20-0:40 Log in as DemoAlice; show the authenticated shell and roster.
3. 0:40-1:00 Start a second client, show wrong-code rejection and duplicate DemoAlice rejection.
4. 1:00-1:20 Disconnect the first client, then reuse DemoAlice in the second.
5. 1:20-1:40 Remove the custom truststore in a fresh client terminal and retry:

```powershell
Remove-Item Env:NETCONNECT_TRUST_STORE,Env:NETCONNECT_TRUST_STORE_PASSWORD -ErrorAction SilentlyContinue
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./server/src/main/resources/com/netconnect/server/security/security-demo.ps1 -Mode ProductionClient
```

The temporary self-signed certificate must fail validation unless it has separately
been added to JVM trust anchors. Restore terminal B configuration to retry.

6. 1:40-2:00 Run the focused TransferTicketServiceTest command (substitute that class
   alone in the first test command above), disconnect, and stop the server.

For file demonstration outside the two-minute sequence, connect two distinct names,
use File Transfers to offer a file, accept in the peer window, and choose a destination.
Chat cannot be demonstrated in this checkout. The standalone Server/Client modes
provide the original independent authentication fallback.

Clear secrets in each terminal after use:

```powershell
Remove-Item Env:NETCONNECT_ACCESS_CODE,Env:NETCONNECT_KEY_STORE_PASSWORD,Env:NETCONNECT_TRUST_STORE_PASSWORD,Env:NETCONNECT_DEMO_STORE_PASSWORD -ErrorAction SilentlyContinue
```

## Limitations and troubleshooting

The full original SRS is absent; validation follows repository protocol/contracts
and the supplied task. Team approval for the named bootstrap, shell and transfer
integration files was explicitly granted during implementation. Shared contracts,
POM files, byte framing, and other members' feature implementations were preserved.
No chat service/view is implemented in this checkout. UDP hint transport is not
started by this integration; the authenticated TLS roster is authoritative and
UDP never grants access. Global rate limiting, production PKI, shared-code rotation,
and a heartbeat/idle policy still need deployment/team decisions.

Wrong certificate/hostname: check SAN, host, and trusted certificate; never disable
verification. Missing/wrong store password: startup fails closed. Port in use: stop
the prior process or set matching NETCONNECT_CONTROL_PORT in both terminals.
For LAN transfer, set NETCONNECT_SERVER_HOST on the server to a reachable address.
If JavaFX reports an unresolved compilation problem after an editor compilation,
run the clean reactor build before relaunching to replace stale target/classes.
Test warning logs from simulated UDP failures are expected test fixtures.

Remaining manual checks: multi-window file acceptance/destination dialogs, exhaustive
accessibility review, minimum window sizing and different display scales, abrupt
LAN loss, and production PKI. These are not claimed as completed acceptance checks.

## Final acceptance - 24 November 2026

The acceptance run of `mvn -B clean verify` passed 90 tests, including 28 security
package tests, with zero failures, errors or skips and no Maven compiler warnings.
Expected negative-test UDP/transfer warnings remain visible in test output.
The JavaFX pane tests also emit an unsupported-configuration warning because they
load JavaFX from the unnamed module; production script launch uses the module path.
This test-runtime warning was not suppressed and no POM was changed to remove it.
The production subprocess test verifies retained TLS transport, session impersonation
rejection, correlated control responses, authenticated file offer/approval, actual
bytes and SHA-256 verification, wrong-role and replay rejection, disconnect/name
reuse, asynchronous shell send/receive, and reader shutdown. Other security tests
cover unauthenticated requests, concurrent names, trust/hostname rejection and expiry.

The actual ProductionServer and two ProductionClient instances were launched using
security-demo.ps1 with temporary one-day certificates and local test credentials.
Launching on the interactive desktop, rather than the isolated execution desktop,
made the JavaFX windows available to the computer-use helper. The earlier lack of
graphical observations is superseded by the following directly observed results:

| Check | Observed result |
| --- | --- |
| Login layout | Dark-blue branding, aligned inputs, rounded controls, readable labels; no clipping at restored or maximized size |
| Password | Masked bullets; cleared on submission and on return to login |
| Keyboard | Tab and Shift+Tab moved through name/code/host fields; visible focus; Enter submitted; Right selected the File Transfers tab |
| Connecting | Progress indicator and disabled form/submission; no premature authenticated status |
| Invalid code | Login denied feedback; password focus restored and retry available |
| Valid login | Verified-TLS identity shown and existing main shell opened |
| Duplicate name | Second client rejected DemoAlice while the first remained authenticated |
| Certificate error | Trusted certificate still rejected for host 127.0.0.2, absent from SAN; clear TLS error and retry available |
| Navigation | Existing Chat, Online Users and File Transfers tabs visible and usable |
| Online Users | DemoAlice displayed in the authoritative one-user TLS roster |
| File Transfers | Existing offer, progress and activity controls rendered; no-file submission disabled; plaintext limitation visible |
| Logout/reconnect | Disconnect returned to a fresh form; the same client logged in again as DemoAlice and reopened the shell |
| Resize/responsiveness | Maximize/restore preserved the form; typing, keyboard navigation, async status and tab selection remained responsive |
| Window close | Both test clients closed through their normal window-close lifecycle |

Full GUI file selection/acceptance is not claimed; byte transfer was verified by
the automated production test. UI responsiveness observations are not a load test.
The transfer pane heading hardcodes port 5052 even when the configured port differs;
this is a cosmetic issue for member 234077D, not a reason to alter the byte protocol.

## Ownership and submission review

The user explicitly granted team approval for integration edits to
server/bootstrap/NetConnectServer.java, client/shell/NetConnectClient.java, and
server/transfer/{TicketRegistry,NioTransferService,NioFileServer}.java.
That approval covers TLS startup, retained authenticated transport, session lifecycle
and ticket validation. It does not imply blanket approval for other members' work.

The following edits were already present before security integration and remain
preserved. Obtain the relevant owner's review before including them in submission:
client/transfer/FileTransferPane.java (unused local removal),
server/transfer/TransferFileNameSanitizer.java (unused imports),
server/session/BoundedSessionExecutorTest.java (test resource cleanup),
server/transfer/{NioFileServerIntegrationTest,NioHandshakeTest}.java (unused imports),
and the pre-existing unused imports/role-field cleanup in NioFileServer.java.
The session test is under server/src/test/java; the two transfer tests are also
under server/src/test/java. The analogous production paths are under src/main/java.
No source, shared contract or POM changes were needed during final acceptance;
only this existing document was updated. No commits or pushes were made.

The individual security module is implemented and acceptance-tested against the
available repository contracts. Complete SRS certification still requires the
original SRS, which is not present. PROTOCOL.md explicitly specifies plaintext v1
file transport, matching the implementation; no discrepancy with that baseline was
found. A broader claim of encrypted file bytes would be incorrect.
Overall team completion remains dependent on member 234229X's absent chat service
and view, and integration of the existing UDP hint lifecycle by the relevant owners.
Those dependencies do not undo the verified security login and TLS authorization.

Suggested separate commits: production TLS/authentication and shell handoff;
approved session-bound ticket integration; security acceptance tests and demo docs.
Keep unrelated pre-existing cleanup separate for its owner's review.
