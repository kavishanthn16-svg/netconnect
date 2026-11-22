# Discovery verification - member 234012C

Verified 22 November 2026 on Windows with Java 21 and Maven 3.10.2.
Command: `mvn -B clean verify`.
Result: BUILD SUCCESS, 37 tests (common 3, server 16, client 18), no failures/errors/skips.
Surefire evidence is regenerated under each module's `target/surefire-reports/`.
The simulated endpoint-send warnings are expected failure-injection test output.

## Acceptance matrix

PASS below means only the stated isolated scope passed. No authenticated TLS session,
mounted production shell, or second machine was used. TestSession is an identity stub,
not proof of authentication. Receiver onAuthenticated is called directly by tests.

| # | Check | Isolated result and evidence | Actual TLS/shell result |
|---|---|---|---|
| 1 | Two authenticated clients, five-second hints | PASS: production scheduler and actual socket send to two loopback ports; two rounds, 3.5-6.5 s interarrival tolerance, current timestamp/source checked | NOT RUN: no authentication wiring |
| 2 | Advertise actual receive port during login | PASS: bound, distinct nonzero ports available via localUdpPort | FAIL: AUTH_LOGIN builder/adapter is absent, so actual advertisement is unimplemented |
| 3 | Two clients on same PC | PASS: real UDP delivery to two receiver instances with distinct ports | NOT RUN: no two authenticated app clients |
| 4 | Loss expires at 15 s | PASS: monotonic model boundary at exactly 15 s; receiver observes expiry without traffic using injected time | NOT RUN: live integrated 15 s loss scenario |
| 5 | Expiry preserves TLS roster | PASS: authoritative-roster adapter supplied directly; pane/state preserve names and revision after expiry | NOT RUN: no real TLS USER_ROSTER dispatch |
| 6 | Recovery restores indicator | PASS: returning valid UDP packet restores receiver state; pane recovery tested separately | NOT RUN: end-to-end receiver-to-mounted-pane recovery |
| 7 | Malformed, stale, stale revision | PASS: codec, model and real receiver tests; rejected packets do not refresh freshness | NOT RUN: integrated/LAN hostile-packet checks |
| 8 | Disconnect endpoint/worker cleanup | PASS: explicit unregisterEndpoint and onDisconnected calls stop delivery/worker | NOT RUN: actual control disconnect does not yet call hooks |
| 9 | UI responsiveness | PASS: actual FX toolkit processes pane update within 1 s while UDP worker waits; controls updated via FX queue | NOT RUN: interactive mounted shell under network load |
| 10 | Shutdown releases resources | PASS: socket closure, server port rebind, scheduler termination, receiver worker termination and login-failure port release | NOT RUN: application shutdown integration |

## Requirement conclusions

- FR-07: PASS at component level for real timed UDP sends, deterministic expiry and roster separation; NOT RUN for authenticated integrated application.
- NFR-01: PASS for Discovery's FX handoff and background receive; NOT RUN for the complete application's UI responsiveness.
- NFR-08: PASS in Discovery: UDP has no authentication/authorization/roster-mutating path; NOT RUN for full control-plane enforcement.
- T05: PASS in isolated receiver/model/pane tests; NOT RUN on two real machines or through real TLS.
- 234012C: DatagramSocket/DatagramPacket, timing, validation, roster/liveness separation and widget are implemented and tested. Login advertisement, lifecycle wiring and pane mounting are still integration gaps. Two-minute live integrated demo: NOT RUN.

Source review confirms bootstrap does not instantiate UdpPresenceService and the shell
still mounts placeholder tabs. Security contains no login implementation. See INTEGRATION.md
for exact existing Discovery hooks. Owners 234106T and 234042R must complete those connections.
Passing tests are not a claim that the whole application is integration-ready.

## Two-machine LAN smoke procedure - NOT RUN

Prerequisite: owners wire authenticated login/udpPort, revision supplier, actual TLS peer IP,
disconnect/shutdown callbacks, TLS USER_ROSTER dispatch and OnlineUsersPane mounting.
The present placeholder launchers alone cannot perform this test.

1. Use the same tested commit on machines A (server) and B (clients), connected to the same
   trusted LAN without wireless client isolation. Build both with `mvn -B clean verify`.
   Record commit SHA, Java/Maven versions, server LAN IPv4, configured ports and test time.
2. Set non-secret configuration in each launching PowerShell. Example defaults:

   ```powershell
   $env:NETCONNECT_CONTROL_PORT = '5050'
   $env:NETCONNECT_UDP_PORT = '5051'
   $env:NETCONNECT_TRANSFER_PORT = '5052'
   # On B, replace with A's actual LAN address:
   $env:NETCONNECT_SERVER_HOST = '<A-LAN-IP>'
   ```

   Use the actual configured values throughout. NetConnectConfig reads environment/system
   properties; it does not load a .env file automatically. Provision access code and truststore
   through the security owner's documented mechanism without recording secrets in evidence.
3. Permit inbound control TCP on A's configured control port and inbound UDP on B's actual
   advertised client receive ports, scoped to the peer IP/private LAN. Permit A's outbound
   UDP from configured source port 5051 to those receive ports. Keep other ports closed unless
   another feature requires them. Client ports are ephemeral and differ per instance.
4. Launch the wired server and two wired clients on B using the repository run commands:

   ```powershell
   # A, repository root:
   java -cp "server\target\classes;common\target\classes" com.netconnect.server.bootstrap.NetConnectServer
   # B, install common once, then run client command in each of two terminals:
   mvn -B -pl common -am install
   mvn -f client\pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run
   ```

   Authenticate using different names. Verify the login adapter advertises each bound receive
   port and only successful authentication registers the endpoint. Capture port numbers without
   logging the complete AUTH_LOGIN payload or credentials.
5. Capture UDP on B's LAN interface using filter `udp.srcport == 5051 && ip.src == <A-LAN-IP>`
   (substitute configured port). Inspect three or more packets per client. Confirm destination
   ports match login registration, exact four-field payload, stable server instance ID,
   plausible Unix timestamps and approximately 5 s per-client intervals. Save sanitized evidence.
6. Keep TLS active. Temporarily block only incoming hints on B from A's configured UDP source
   port, using a narrowly scoped temporary firewall rule with a unique name. Do not kill the
   server or disconnect Wi-Fi. Start timing at the last accepted hint. After 15 s, both panes
   should show missing UDP hints; names/revision should remain unchanged without genuine TLS
   roster events. Interact with/resize the UI throughout and confirm it remains responsive.
7. Remove that temporary rule. Within the next heartbeat interval (allow normal LAN jitter),
   confirm fresh-hint labels return and the TLS roster remains authoritative. Capture evidence.
8. Disconnect one client gracefully, then repeat with abrupt termination. Verify server endpoint
   removal and continued delivery to the remaining client. Verify workers terminate on graceful
   close. A killed process's OS cleanup alone does not prove the lifecycle callback ran.
9. Shut down both clients and server normally. Confirm the configured server UDP port can be
   rebound and that presence workers/scheduler have terminated. Remove only test firewall rules.
10. Record each case as PASS/FAIL/NOT RUN, expected/actual behavior and sanitized screenshots,
    packet timings and logs. Do not mark the test PASS until real authentication and wiring ran.

For the two-minute member demo, show fresh hints, block UDP while TLS stays active, wait 15 s,
show unchanged roster, remove the block, show recovery, then show one automated test result.
