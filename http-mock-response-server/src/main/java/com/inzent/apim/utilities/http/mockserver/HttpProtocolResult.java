package com.inzent.apim.utilities.http.mockserver;

import java.util.List;
import java.util.Objects;

/** Snapshot after one read. Completed requests belong only to that read, not connection history. */
public record HttpProtocolResult(HttpRequestSnapshot request,
        List<HttpRequestSnapshot> completedRequests, boolean endOfInput) {
    public HttpProtocolResult {
        Objects.requireNonNull(request);
        completedRequests = List.copyOf(completedRequests);
    }
    public boolean truncated() {
        return endOfInput && request.initialLineState() != HttpReceiveState.NOT_REACHED && !request.complete();
    }
    static HttpProtocolResult empty() {
        return new HttpProtocolResult(HttpRequestSnapshot.empty(), List.of(), false);
    }
}
