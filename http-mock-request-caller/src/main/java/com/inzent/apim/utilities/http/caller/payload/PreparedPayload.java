package com.inzent.apim.utilities.http.caller.payload;
import com.inzent.apim.utilities.http.caller.transport.BodyStore;
import java.io.IOException;
import java.util.*;
public record PreparedPayload(Map<String,List<String>> headers, BodyStore body) implements AutoCloseable {
    public PreparedPayload {
        Map<String,List<String>> copy = new LinkedHashMap<>();
        headers.forEach((k,v) -> copy.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
        headers = Collections.unmodifiableMap(copy); Objects.requireNonNull(body);
    }
    @Override public void close() throws IOException { body.close(); }
}
