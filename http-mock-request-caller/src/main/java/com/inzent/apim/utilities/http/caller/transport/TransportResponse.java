package com.inzent.apim.utilities.http.caller.transport;
import java.io.IOException;
import java.util.*;
public record TransportResponse(int status, Map<String,List<String>> headers, BodyStore body,
                                long elapsedNanos, long sentBytes) implements AutoCloseable {
    public TransportResponse {
        Map<String,List<String>> copy = new LinkedHashMap<>();
        headers.forEach((k,v) -> copy.put(k,List.copyOf(v))); headers = Collections.unmodifiableMap(copy);
    }
    @Override public void close() throws IOException { body.close(); }
}
