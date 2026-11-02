package com.inzent.apim.utilities.netty.http.mockserver.server;

import io.netty.util.concurrent.ScheduledFuture;

/** Confined to a single channel's event loop. */
final class ConnectionState {
    boolean terminal;
    int pendingResponses;
    ScheduledFuture<?> requestDeadline;
    ScheduledFuture<?> delayedClose;
    void cancelRequestDeadline() {
        if (requestDeadline != null) { requestDeadline.cancel(false); requestDeadline = null; }
    }
    void cancelAll() {
        cancelRequestDeadline();
        if (delayedClose != null) { delayedClose.cancel(false); delayedClose = null; }
    }
}
