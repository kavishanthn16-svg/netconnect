# Discovery handoff - 234012C

## Snapshot and ownership

Prepared 22 November 2026. Source branch: `akeel`. PR target: `development`.
Inspected HEAD: `0b2f1b1a5c95e5ce65db84e415ed71c23c852a04`.
Local development reference: `131e61d5806a460a5adab4c18ef873bee8818a11`.
Remote freshness and formal team approval of a shared foundation base were not independently verified.
The user authorized working on akeel. The branch originated from main history rather than the
document's requested shared development base; disclose that provenance in review.

`git diff --name-status origin/development` showed only 17 Discovery additions before this
handoff. This document makes 18 feature files. No common, bootstrap, session, shell,
security, chat, Maven configuration, .env or shared resource edits are part of this handoff.
The comparison is against the local remote-tracking reference; no fetch was performed here.

All paths below are relative to the repository root and owned by 234012C:

```text
server/src/main/java/com/netconnect/server/discovery/
  PresenceDatagramEncoder.java
  UdpPresenceService.java
server/src/test/java/com/netconnect/server/discovery/
  PresenceDatagramEncoderTest.java
  UdpPresenceServiceTest.java
client/src/main/java/com/netconnect/client/discovery/
  PresenceHint.java
  PresenceDatagramDecoder.java
  PresenceLiveness.java
  UdpPresenceReceiver.java
  OnlineUsersState.java
  OnlineUsersPane.java
  INTEGRATION.md
  VERIFICATION.md
  HANDOFF.md
client/src/test/java/com/netconnect/client/discovery/
  PresenceDatagramDecoderTest.java
  PresenceLivenessTest.java
  UdpPresenceReceiverTest.java
  OnlineUsersStateTest.java
  OnlineUsersPaneTest.java
```

Documentation stays alongside the existing approved Discovery documentation. Suggested
member notes/evidence below are drafts for the team to place in approved central locations;
no central docs or evidence files were changed automatically.

## Validation and commands

Run from repository root with Java 21 selected:

```powershell
mvn -B clean verify
mvn -B -pl client -am '-Dtest=UdpPresenceReceiverTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Actual outcomes on 22 November:

- Full verification in the preceding verification step: BUILD SUCCESS; 37 tests,
  zero failures/errors/skips (common 3, server 16, client 18).
- Focused receiver command rerun during handoff: BUILD SUCCESS; 5 tests,
  zero failures/errors/skips; Maven duration 2.739 s.
- Real loopback sockets exercised two client receive ports, the production five-second
  scheduler, payload/source validation, loss/recovery, endpoint cleanup and socket release.
- Timeout boundaries use injected time rather than waiting 15 s. JavaFX tests use the real
  toolkit and check FX handoff while a UDP worker waits; they are not an interactive LAN demo.
- Full application TLS/shell and two-machine LAN acceptance: NOT RUN. AUTH_LOGIN port
  advertisement is absent, not a passing integration test. See VERIFICATION.md for all ten cases.
- Expected warning output includes deliberate send-failure injection and JavaFX unnamed-module startup.

If mvn is not on PATH, the verified local command prefix is:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.13.1'
& 'C:\Program Files\apache-maven-3.10.2\bin\mvn.cmd' -B clean verify
```

The focused test run can overwrite its Surefire report timestamp; the full-run totals above
refer to the preceding complete build, not to a newly inferred aggregate of report timestamps.

## Networking explanation

DatagramSocket binds the server source port or a client's receive port and sends/receives UDP.
DatagramPacket carries one bounded message plus source/destination addressing. The format is
UTF-8 `PRESENCE_V1|serverId|rosterRevision|unixMillis`, capped locally at 512 bytes.
The receiver allocates 513 bytes so oversized packets cannot become valid truncated prefixes.

UDP can lose, duplicate or reorder packets and provides no delivery or authentication guarantee.
The server schedules hints every five seconds; fifteen seconds without an accepted hint expires
the liveness marker. Packet age is checked against wall time, while ongoing timeout uses monotonic
time so system-clock adjustments do not change the elapsed timeout. New valid hints restore it.

TLS USER_ROSTER alone changes displayed membership because it comes through the authenticated
control channel. A UDP timeout might reflect filtering or loss while TCP still works. Removing
users because UDP stopped would incorrectly turn an unreliable hint into membership authority.

## Integration responsibilities

234106T (bootstrap/session/shell):

- Instantiate/start UdpPresenceService with config, server-instance ID, authenticated-address
  resolver and roster-revision supplier; call stop on shutdown.
- Supply revision via the current registry API. Do not perform UDP I/O inside registry callbacks.
- Call unregisterEndpoint(sessionId) on every actual disconnect and cleanup path.
- Construct OnlineUsersPane on FX thread and mount it in the Online Users tab. There is currently
  no implemented shell extension interface; this owner must supply the hook or replace its placeholder.
- Coordinate pane reset and detach old connection callbacks before reconnecting.

234042R (authentication/TLS), coordinated with 234106T:

- On a background login worker, create UdpPresenceReceiver(pane::onLivenessChanged) before login
  and put its actual localUdpPort into the approved optional AUTH_LOGIN udpPort field.
- Validate port range and registerEndpoint only after successful authentication. Resolve client
  IP from the actual TLS transport, never a client-supplied IP field.
- After AUTH_OK, call receiver.onAuthenticated with the actual TLS peer address and configured
  server UDP port. Pass trusted server ID only if an approved trusted source exists.
- Deliver validated TLS USER_ROSTER revision/users to pane.onAuthoritativeRoster.
- Call onLoginFailed/onDisconnected/close on receiver as appropriate; use a new receiver for reconnect.
- Never use UDP to authorize login, membership or file transfers.

Exact code snippets and method signatures are in INTEGRATION.md. No shared interface changes
or new control event types are introduced by this feature.

## Limitations

- Production bootstrap/login/shell integration is absent; component tests call hooks directly
  and use Session identity stubs. This is not full authenticated application acceptance.
- Source IP/port checks and identity pinning do not cryptographically authenticate UDP.
- Lower UDP revisions and non-increasing timestamps are rejected; equal revisions with newer
  timestamps are valid. Hints older than 15 s or more than 5 s into the future are rejected.
- Clock skew beyond the tolerance can suppress hints. Wall-clock rollback can temporarily
  prevent new timestamps from passing replay checks; monotonic expiry still works.
- A restarted server needs a new authenticated connection/reset before accepting its new identity.
- stop/close is terminal; construct new service/receiver instances to restart or reconnect.
- Listener callbacks must remain short; blocking a receiver callback delays reception/expiry reporting.
- No server auto-search, NAT traversal, LAN run, screenshot evidence or live demo has been claimed.

## PR draft

Title: `[234012C] Add UDP presence hints and online-users pane`

Target: `development`; source: `akeel`.

NetConnect needs UDP server-liveness hints separate from its authoritative TLS roster.
This adds a five-second DatagramSocket sender, background client receiver, bounded UTF-8
codec, fifteen-second expiry/recovery model and reusable OnlineUsersPane. Packet loss
changes only the liveness label; roster membership changes only through the TLS adapter.

Owner/index: 234012C. Allowed paths: server/client discovery packages and corresponding tests.
Contract: SRS v1.0 four-field PRESENCE_V1 format; server default UDP 5051; distinct advertised
client receive ports. No dependencies, Maven files, shared DTOs or common interfaces changed.
Base/reference and branch provenance: see Snapshot and ownership above; approval unverified locally.

Validation: mvn -B clean verify passed 37 tests; focused UdpPresenceReceiverTest passed 5.
Tests include real loopback UDP, production five-second timing, malformed/replayed/stale hints,
deterministic expiry/recovery, FX handoff and cleanup. Automated component validation passes.

Integration follow-up: 234106T must wire startup/shutdown, roster revision, session cleanup and
pane mounting; 234042R must wire authenticated IP, AUTH_LOGIN udpPort and TLS roster dispatch.
Full TLS/shell integration and LAN demo remain NOT RUN. No screenshot evidence is attached yet.
Shared files changed: NO. Ready for feature review; full integration acceptance remains pending.

## Suggested member note (draft only)

I implemented NetConnect Discovery under index 234012C: UDP datagram codec, five-second
server sender, background client receiver, fifteen-second monotonic liveness expiry,
validation/replay handling and OnlineUsersPane. TLS owns the roster, so missing UDP hints
never remove users. Automated verification passed 37 tests, including 26 Discovery tests.
Authentication, lifecycle and shell integration are pending the other owners; no full LAN
or live integrated demo result is claimed. My networking classes are DatagramSocket and
DatagramPacket, with synchronized endpoint access and explicit socket/thread cleanup.

## Suggested sanitized evidence (not fabricated or created)

After approval, place these under the team's docs/evidence location:

- Full build summary: date, commit, command, module results, 37-test outcome and limitations.
- Focused receiver-test output showing five passing tests.
- UDP capture excerpt: protocol marker, anonymized server ID/IP, revision, timestamps,
  source/destination ports and per-client intervals. Omit unrelated traffic.
- Two-client screenshots before loss, at expiry and after recovery, with non-sensitive demo names.
- A 10-case matrix copied from VERIFICATION.md and updated only after observed LAN outcomes.
- Cleanup evidence from a graceful disconnect and normal shutdown.

Never capture .env, access codes, session tokens, full login payloads, private keys or truststore passwords.
Screenshots and LAN logs do not exist yet; retain NOT RUN until actually captured.

## Two-minute demo plan (prepared, NOT RUN)

Prerequisites outside the timer: complete owner integration, launch server on machine A and
two authenticated clients on B; display the roster/pane and packet capture; warm Maven cache.
Use a trusted private IPv4 LAN. Have an elevated PowerShell ready on A with the variables below.
Do not use stop/start on UdpPresenceService: stop is terminal. Instead block only outbound UDP
hints at the server, leaving TCP/TLS untouched. The scheduler continues attempting sends;
the firewall pauses delivery onto the LAN, not the scheduler itself. This is a loss simulation.

```powershell
# Replace these values with the configured server UDP source port and actual client-machine IP.
$demoUdpPort = 5051
$demoClientIp = '<B-LAN-IP>'
$demoRuleName = 'NetConnect-234012C-Demo-Pause-UDP'
# Precheck once; if a rule already has this name, choose a unique name rather than replacing it.
Get-NetFirewallRule -Name $demoRuleName -ErrorAction SilentlyContinue
```

Prepared pause command (run on A, administrative terminal):

```powershell
New-NetFirewallRule -Name $demoRuleName -DisplayName $demoRuleName -Direction Outbound -Action Block -Protocol UDP -LocalPort $demoUdpPort -RemoteAddress $demoClientIp -Profile Private | Out-Null
```

Prepared resume/cleanup command:

```powershell
Remove-NetFirewallRule -Name $demoRuleName
```

These commands were documented only, not executed. Rehearse before evaluation to verify the
active Private profile and host policy allow the rule. Remove only this test rule afterward,
including if the demo is interrupted. See VERIFICATION.md for LAN setup and configured ports.

| Time | Action and words |
|---|---|
| 0:00-0:20 | Show two clients, their distinct receive ports and hints approximately every 5 s. Point to each separate UDP liveness label. |
| 0:20-0:30 | Run prepared pause command after a fresh hint; show TLS still connected. Explain: only UDP delivery is blocked. |
| 0:30-0:50 | Wait at least 15 s from last accepted hint; interact with the UI, show the missing-hints label and unchanged roster. |
| 0:50-1:05 | Run resume command. Wait for the next 5 s heartbeat plus normal jitter; show fresh labels return. |
| 1:05-1:35 | Run the focused receiver test command above and show five passing tests; have the honest previously captured result ready if startup is slow. |
| 1:35-1:50 | Explain UDP loss and DatagramPacket, why TLS owns roster membership, and point to owned source files. |

Target duration: 110 s, leaving 10 s buffer. The plan is not presently executable through the
placeholder application; rehearse after integration before claiming a successful demonstration.
