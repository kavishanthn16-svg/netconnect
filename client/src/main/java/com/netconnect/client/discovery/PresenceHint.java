package com.netconnect.client.discovery;

/** Parsed hint only; never an authoritative roster or authentication result. */
public record PresenceHint(String serverId, long rosterRevision, long unixMillis) { }
