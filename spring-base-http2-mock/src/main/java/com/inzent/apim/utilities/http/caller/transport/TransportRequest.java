package com.inzent.apim.utilities.http.caller.transport;
import java.net.URI;
import java.util.*;
public record TransportRequest(URI uri, String method, Map<String,List<String>> headers, BodyStore body, boolean chunked) {
    public TransportRequest {
        Objects.requireNonNull(uri); Objects.requireNonNull(method); Objects.requireNonNull(body);
        Map<String,List<String>> copy = new LinkedHashMap<>();
        headers.forEach((k,v) -> copy.put(k,List.copyOf(v))); headers = Collections.unmodifiableMap(copy);
    }
}
