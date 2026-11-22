# OnlineUsersPane integration

The current shell has private placeholder tabs and **no implemented shell extension interface**.
234106T must supply the mount hook or replace the Online Users placeholder in its own reviewed change.
No shared extension interface is invented by Discovery.

## Shell owner (234106T)

Construct and mount on the JavaFX Application Thread, e.g. in Application.start:

```java
OnlineUsersPane onlineUsersPane = new OnlineUsersPane();
Tab onlineUsersTab = new Tab("Online Users", onlineUsersPane);
onlineUsersTab.setClosable(false);
// Add onlineUsersTab to the shell's existing TabPane instead of its placeholder.
```

If the approved shell mount method is added later, pass `onlineUsersPane` as its Node content.
Keep the pane reference for the TLS dispatcher and receiver listener.

## Login/control owner (234042R), with shell lifecycle owner

On a background login worker, before building AUTH_LOGIN:

```java
UdpPresenceReceiver receiver = new UdpPresenceReceiver(onlineUsersPane::onLivenessChanged);
int udpPort = receiver.localUdpPort();
// Add optional integer udpPort to the existing approved AUTH_LOGIN payload.
```

Only after successful TLS authentication:

```java
receiver.onAuthenticated(new InetSocketAddress(tlsSocket.getInetAddress(), config.udpPort()));
```

The source address must be the actual authenticated TLS peer, not another user-provided IP.
If a trusted server ID is available, use the two-argument overload; otherwise the first
accepted hint pins identity. UDP remains an unauthenticated hint.

Dispatch each validated USER_ROSTER payload from the authenticated TLS connection:

```java
onlineUsersPane.onAuthoritativeRoster(revision, displayNames);
```

`displayNames` is the validated `users` array and `revision` is its nonnegative integer revision.
These methods queue updates with Platform.runLater; callbacks must not block the FX thread.
Do not feed UDP revisions or UDP membership into this method.

Call `receiver.onLoginFailed()` on login failure, `receiver.onDisconnected()` on TLS disconnect,
and `receiver.close()` on application shutdown. They are idempotent. They do not clear the pane:
the TLS lifecycle owner must detach old callbacks, then call `onlineUsersPane.resetForConnection()`
to clear the roster and revision when appropriate. Create a new receiver for reconnect.
Prefer a new pane per connection; do not allow queued callbacks from an old connection
to update a new connection's pane. Never reset the roster in response to UDP timeout.

This component does not establish TLS, start socket workers, or modify shell/security code.
