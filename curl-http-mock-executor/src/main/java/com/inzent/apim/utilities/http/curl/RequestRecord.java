package com.inzent.apim.utilities.http.curl;

import io.netty.handler.codec.http.*;
import java.util.*;

/** Prepared entity and headers; stdin byte count is not proof of server receipt. */
public final class RequestRecord {
    private HttpHeaders headers = EmptyHttpHeaders.INSTANCE;
    private BodyStore body;
    private final List<HttpHeaders> sentHeaders = new ArrayList<>();
    private long stdinBytesWritten;
    static HttpHeaders snapshot(HttpHeaders source) {
        return new ReadOnlyHttpHeaders(true, source.entries().stream()
            .flatMap(e -> java.util.stream.Stream.of((CharSequence)e.getKey(), (CharSequence)e.getValue()))
            .toArray(CharSequence[]::new));
    }
    void prepare(HttpHeaders h, BodyStore b) { headers = snapshot(h); body = b; }
    void sent(HttpHeaders h) { sentHeaders.add(snapshot(h)); }
    void written(long count) { stdinBytesWritten = count; }
    public HttpHeaders headers() { return headers; }
    public Optional<BodyStore> body() { return Optional.ofNullable(body); }
    public List<HttpHeaders> sentHeaders() { return List.copyOf(sentHeaders); }
    public long stdinBytesWritten() { return stdinBytesWritten; }
}
