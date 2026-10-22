package com.inzent.apim.utilities.http.mockserver;

import java.time.Duration;

public final class CloseClientException extends NextActionException {
    private final Duration closeDelay;
    public CloseClientException() { this(Duration.ZERO); }
    public CloseClientException(Duration closeDelay) {
        super("Close client after " + closeDelay);
        Durations.nanos(closeDelay, "closeDelay", true);
        this.closeDelay = closeDelay;
    }
    public Duration closeDelay() { return closeDelay; }
}
