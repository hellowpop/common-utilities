package com.inzent.apim.utilities.http.curl;

import io.netty.handler.codec.http.*;
import java.util.*;

/** Received response blocks and entity, including partial data after a failed transfer. */
public final class ResponseRecord {
    public record HeaderBlock(HttpResponseStatus status, HttpHeaders headers) {
        public HeaderBlock { Objects.requireNonNull(status,"status"); headers = RequestRecord.snapshot(Objects.requireNonNull(headers,"headers")); }
    }
    private final List<HeaderBlock> blocks = new ArrayList<>();
    private HttpResponseStatus status;
    private HttpHeaders headers = EmptyHttpHeaders.INSTANCE;
    private BodyStore body;
    private boolean bodyComplete;
    void add(HttpResponseStatus s, HttpHeaders h) {
        blocks.add(new HeaderBlock(s,h));
        if (s.code() >= 200) { status = s; headers = RequestRecord.snapshot(h); }
    }
    void body(BodyStore value, boolean complete) { body = value; bodyComplete = complete; }
    public List<HeaderBlock> headerBlocks() { return List.copyOf(blocks); }
    public Optional<HttpResponseStatus> status() { return Optional.ofNullable(status); }
    public HttpHeaders headers() { return headers; }
    public Optional<BodyStore> body() { return Optional.ofNullable(body); }
    public boolean bodyComplete() { return bodyComplete; }
}
