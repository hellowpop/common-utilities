package com.inzent.apim.utilities.http.mockserver;

import java.time.Duration;
import java.util.Arrays;
import java.util.IdentityHashMap;

/** Bounded diagnostics without references to the original throwable or its payload. */
public final class ThrowableSnapshot extends Exception {
    private final String originalType;
    private final Duration closeDelay;
    private final ServiceEvent.ResponseMetadata response;

    private ThrowableSnapshot(Throwable source, String message) {
        super(message);
        originalType = source instanceof ThrowableSnapshot s ? s.originalType : source.getClass().getName();
        closeDelay = source instanceof CloseClientException c ? c.closeDelay()
            : source instanceof ThrowableSnapshot s ? s.closeDelay : null;
        response = source instanceof SendResponseException r ? r.response().metadata()
            : source instanceof ThrowableSnapshot s ? s.response : null;
    }
    public String originalType() { return originalType; }
    public Duration closeDelay() { return closeDelay; }
    public ServiceEvent.ResponseMetadata response() { return response; }

    static ThrowableSnapshot capture(Throwable source) {
        return copy(source, new IdentityHashMap<>(), 0);
    }
    private static ThrowableSnapshot copy(Throwable source, IdentityHashMap<Throwable, Boolean> seen, int depth) {
        if (source == null || depth >= 8 || seen.size() >= 64 || seen.put(source, true) != null) return null;
        String message;
        try { message = source.getMessage(); }
        catch (Exception unavailable) { message = "Diagnostic message unavailable"; }
        if (message != null && message.length() > 4096) message = message.substring(0, 4096);
        var result = new ThrowableSnapshot(source, message);
        try {
            StackTraceElement[] frames = source.getStackTrace();
            result.setStackTrace(Arrays.copyOf(frames, Math.min(frames.length, 64)));
            result.initCause(copy(source.getCause(), seen, depth + 1));
            Throwable[] suppressed = source.getSuppressed();
            for (int i = 0; i < Math.min(suppressed.length, 8); i++) {
                ThrowableSnapshot child = copy(suppressed[i], seen, depth + 1);
                if (child != null) result.addSuppressed(child);
            }
        } catch (Exception unavailable) { /* Keep the diagnostics already captured. */ }
        return result;
    }
}
