# NetConnect security — member 234042R

## Architecture and scope

`EnvironmentAuthService` implements the shared `AuthService`: environment-only access code, constant-time byte comparison, 3–24 character nonblank names without control characters, UUID identities and atomic case-insensitive name reservation through `SessionRegistry`. `AuthenticationState.permits` is a public connection-bound policy for protected chat/file-control requests: both the envelope session ID and the active registry identity must match. UDP discovery never grants access. Removing a registry session immediately invalidates authorization and its tickets.

`TlsControlServer.bind` provides TLS socket primitives for the foundation accept loop; TLS 1.3 is default, TLS 1.2 requires explicit opt-in. `serve` is authentication-only, not production feature routing. AUTH_LOGIN returns correlated AUTH_OK (sessionId/displayName in payload and sessionId in envelope) or AUTH_DENIED (bounded code and safe message). Other requests return ERROR; no feature operation is dispatched. Malformed/unbounded envelopes close that connection and remove its identity. Handshake/unauthenticated read timeout is 15 seconds. Authenticated demo sockets stay open until disconnect; production liveness belongs to the shell/session owner.

`SecurityDemoServer` is an explicitly local development launcher, bound to loopback, with eight workers and a bounded queue of sixteen. It loads a PKCS12 keystore directly from environment configuration, never passwords on the command line. Ctrl+C closes all accepted sockets and the listener. `SecureLoginConnection` validates certificate chains and HTTPS endpoint identity, uses 5-second connect and 10-second read/handshake timeouts, bounds messages, preserves request correlation and closes failed/denied connections. No trust-all manager exists.

`SecureLoginPane` is reusable with an optional authenticated-envelope callback for future shell handoff. It uses JavaFX tasks outside the UI thread, PasswordField, host/port validation, disabled submissions, safe bounded rejection messages, retry and an authenticated/disconnect view. `SecureLoginLauncher` retains ownership of its socket and closes it off the FX thread. The local stylesheet affects only security UI.

`TransferTicketService` is a security-local API, not a new shared contract. `issue(transferId, authenticatedSession, SEND|RECEIVE)` creates a 256-bit unpredictable token, held only server-side, valid for exactly 60 seconds. `consume` atomically validates the token's transfer, active session and role, then removes it; simultaneous replay has one winner. Invalid attempts do not consume a valid ticket. `revokeSession` permits eager cleanup; removed sessions are always rejected. Expired entries are purged on API calls and outstanding tickets are capped at 10,000. The package-private metadata validator is not a wire ticket and must never be populated from untrusted input. Transfer approval and authenticated connection binding remain caller responsibilities.

## Environment

| Variable | Purpose |
| --- | --- |
| NETCONNECT_ACCESS_CODE | Required server shared secret; absent/blank fails closed |
| NETCONNECT_KEY_STORE | Required demo server PKCS12 path |
| NETCONNECT_KEY_STORE_PASSWORD | Required demo keystore/key password |
| NETCONNECT_TRUST_STORE | Optional client PKCS12 truststore; otherwise JVM trust anchors |
| NETCONNECT_TRUST_STORE_PASSWORD | Client truststore password |
| NETCONNECT_CONTROL_PORT | Default 5050 |
| NETCONNECT_SERVER_HOST | Client default localhost |
| NETCONNECT_ALLOW_TLS12 | Explicit true enables TLS 1.2 alongside 1.3 |
| NETCONNECT_DEMO_STORE_PASSWORD | Only used by development keytool commands |

For production bootstrap using `TlsControlServer.bind`, the owner must configure JSSE keyStore/keyStoreType/keyStorePassword before default context initialization. The demo uses its own context instead and does not mutate global JSSE configuration.

## Exact Windows PowerShell setup

Use Java 21 and Maven on PATH. From the repository root:

```powershell
Set-Location D:\TeamProject\netConnect
mvn -B clean verify
```

Maven is not on PATH on this workstation; the existing installation can be used without installing anything:

```powershell
$env:Path = "C:\Users\ASUS\.m2\wrapper\dists\apache-maven-3.9.12-bin\5nmfsn99br87k5d4ajlekdq10k\apache-maven-3.9.12\bin;$env:Path"
```

In server terminal A, generate local-only development keys outside the repository. Choose a strong password at the prompt; neither it nor the access code is printed or embedded in source/history. Environment variables are process-local and remain sensitive; clear them after use. These commands use Windows PowerShell's secure-string credential conversion and Java keytool's environment password option.

```powershell
$demo = Join-Path $env:TEMP 'netconnect-local-demo'
New-Item -ItemType Directory -Force $demo | Out-Null
$env:NETCONNECT_DEMO_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Development store password (at least 6 characters)' -AsSecureString)).Password
keytool -genkeypair -alias netconnect -keyalg RSA -keysize 3072 -validity 7 -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 -keystore "$demo/server.p12" -storepass:env NETCONNECT_DEMO_STORE_PASSWORD
keytool -exportcert -alias netconnect -keystore "$demo/server.p12" -storepass:env NETCONNECT_DEMO_STORE_PASSWORD -file "$demo/server.cer"
keytool -importcert -noprompt -alias netconnect -file "$demo/server.cer" -storetype PKCS12 -keystore "$demo/trusted.p12" -storepass:env NETCONNECT_DEMO_STORE_PASSWORD
$env:NETCONNECT_KEY_STORE = "$demo/server.p12"
$env:NETCONNECT_KEY_STORE_PASSWORD = $env:NETCONNECT_DEMO_STORE_PASSWORD
$env:NETCONNECT_ACCESS_CODE = [System.Net.NetworkCredential]::new('', (Read-Host 'Choose a demo access code' -AsSecureString)).Password
& ./server/src/main/resources/com/netconnect/server/security/security-demo.ps1 -Mode Server
```

Expected: `NetConnect local TLS authentication demo listening on localhost/127.0.0.1:5050` (loopback address representation may vary). Use a fresh directory if the alias already exists. Never commit private keys, generated stores or real secrets. Confirm public-certificate fingerprints out of band before trusting a LAN server; these localhost certificates are only for development.

In client terminal B:

```powershell
Set-Location D:\TeamProject\netConnect
$demo = Join-Path $env:TEMP 'netconnect-local-demo'
$env:NETCONNECT_TRUST_STORE = "$demo/trusted.p12"
$env:NETCONNECT_TRUST_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Same development store password' -AsSecureString)).Password
& ./server/src/main/resources/com/netconnect/server/security/security-demo.ps1 -Mode Client
```

The script uses already built classes and existing Maven-cache Jackson/JavaFX jars; no POM changes, Maven install, or dependency downloads are required. It assumes the existing dependency versions and default Maven repository location. JavaFX GUI launch must occur on an unlocked interactive Windows desktop.

## Two-minute presentation (setup/build beforehand)

1. **0:00–0:15:** Start terminal A with the Server command above. Start terminal B with the Client command. Point out TLS certificate verification and localhost:5050.
2. **0:15–0:35:** Enter `DemoAlice` and the access code chosen at the server prompt. Connect. Expected: authenticated view, display name and session status. Password is masked and cleared on submission.
3. **0:35–0:55:** Leave that window connected. Launch a second Client from terminal C configured like B. Enter `DemoBob` and a deliberately incorrect code: expected login denied. Retry with `DemoAlice` and the correct code: expected name already in use. This is the real TLS server, not mock authentication.
4. **0:55–1:10:** Disconnect the first window; its login form returns. Retry `DemoAlice` in the second window: expected success after server processes EOF. Close both windows.
5. **1:10–1:30:** In B remove the custom truststore and relaunch. The self-signed certificate must be rejected with TLS feedback:

```powershell
Remove-Item Env:NETCONNECT_TRUST_STORE
Remove-Item Env:NETCONNECT_TRUST_STORE_PASSWORD
& ./server/src/main/resources/com/netconnect/server/security/security-demo.ps1 -Mode Client
```

Enter valid-format credentials and Connect. Expected TLS verification failure (assuming this temporary certificate was not added to JVM trust anchors). Restore the trusted configuration using the B setup to retry successfully.

6. **1:30–2:00:** Show one focused security test (pre-run before presenting if key generation/build is slow):

```powershell
mvn -B -pl server -am '-Dtest=TransferTicketServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: four ticket tests, zero failures, BUILD SUCCESS. Explain expiry, identity/role binding and one-time use.

Stop the demo server with **Ctrl+C in terminal A**. With a client window open, connecting after stopping the server must show connection failure and allow retry. Close windows to release sockets. Clear secrets in each relevant terminal:

```powershell
Remove-Item Env:NETCONNECT_ACCESS_CODE,Env:NETCONNECT_KEY_STORE_PASSWORD,Env:NETCONNECT_TRUST_STORE_PASSWORD,Env:NETCONNECT_DEMO_STORE_PASSWORD -ErrorAction SilentlyContinue
```

## Integration dependencies and limitations

Available foundation declarations include AuthService, Envelope, SessionRegistry and ControlGateway, but their decisions document still requires team approval. The ControlGateway interface provides send only; no approved socket ownership/dispatch lifecycle or shell extension contract is defined. No transfer-ticket interface/wire issuance contract is approved. No replacement shared interfaces or production accept loop were introduced. The foundation owner must call authorization before protected dispatch, attach authenticated identities to real transport sessions, remove them on disconnect, and manage liveness. The transfer owner must invoke consume only after approved transfer decisions and with the authenticated connection identity. Shell callbacks and retained socket handoff need agreement. Until those are wired, production chat/file authorization is not claimed complete.

The full original SRS PDF is absent; checks follow the supplied requirements and repository protocol baseline. No absolute session expiry is specified; active registry membership is authoritative. Demo sessions have no authenticated idle timeout, which is useful for presenting but needs a production heartbeat policy. TLS protects control only: v1 NIO file bytes remain plaintext. Shared-code rotation, rate limits and production PKI provisioning remain deployment concerns. Java Strings/environment credentials cannot be reliably wiped from process memory. Never log envelopes containing access codes.

## Troubleshooting and validation

Wrong host/certificate: ensure SAN matches localhost or 127.0.0.1 and the client trusts the actual server certificate. Never disable hostname checking. Missing store/wrong password: startup fails; check paths and prompt input. Address in use: stop the other server or set NETCONNECT_CONTROL_PORT identically in both terminals. Handshake failures close sockets and produce safe generic server diagnostics. Dependency errors in the helper: run the build and verify the configured Maven cache location. No UI: launch from an interactive desktop with Java 21 and cached Windows JavaFX jars.

Automated tests cover credentials, duplicate names, session invalidation/cleanup, TLS trust and hostname checks, explicit TLS 1.2 opt-in, handshake failures, malformed responses, timeout/retry, unavailable server and atomic ticket replay. A separate actual demo-server/client smoke run verified real successful/denied login, duplicate rejection, disconnect/name reuse and untrusted-certificate rejection. UI observations and final build totals are reported in the task completion report; unobserved visual scenarios must remain pending.
