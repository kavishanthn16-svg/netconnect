package com.netconnect.server.session;

import java.util.List;

/** Internal hook for the future control/message owners; it does not implement UDP presence. */
@FunctionalInterface
public interface RosterChangeListener {
    void onRosterChanged(long revision, List<String> users);
}
