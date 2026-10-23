package com.inzent.apim.utilities.http.mockserver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.inzent.apim.utilities.http.mockserver.HttpReceiveState.*;

/** Immutable request data. Header/trailer names are lower-case; content is dechunked bytes. */
public final class HttpRequestSnapshot {
    private final String initialLine;
    private final Map<String, List<String>> headers;
    private final HttpContentBuffer.Snapshot content;
    private final Map<String, List<String>> trailers;
    private final HttpReceiveState initialLineState;
    private final HttpReceiveState headerState;
    private final HttpReceiveState contentState;
    private final HttpReceiveState trailersState;

    public HttpRequestSnapshot(String initialLine, Map<String, List<String>> headers,
            byte[] content, Map<String, List<String>> trailers, HttpReceiveState initialLineState,
            HttpReceiveState headerState, HttpReceiveState contentState, HttpReceiveState trailersState) {
        this(initialLine, copyFields(headers), HttpContentBuffer.copyOf(content), copyFields(trailers),
            initialLineState, headerState, contentState, trailersState);
    }
    /** Internal constructor accepts already deeply immutable field maps and content. */
    HttpRequestSnapshot(String initialLine, Map<String, List<String>> headers,
            HttpContentBuffer.Snapshot content, Map<String, List<String>> trailers, HttpReceiveState initialLineState,
            HttpReceiveState headerState, HttpReceiveState contentState, HttpReceiveState trailersState) {
        this.initialLine = Objects.requireNonNull(initialLine);
        this.headers = Objects.requireNonNull(headers);
        this.content = Objects.requireNonNull(content);
        this.trailers = Objects.requireNonNull(trailers);
        this.initialLineState = Objects.requireNonNull(initialLineState);
        this.headerState = Objects.requireNonNull(headerState);
        this.contentState = Objects.requireNonNull(contentState);
        this.trailersState = Objects.requireNonNull(trailersState);
    }
    static Map<String, List<String>> copyFields(Map<String, List<String>> source) {
        var copy = new LinkedHashMap<String, List<String>>();
        source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return java.util.Collections.unmodifiableMap(copy);
    }
    public String initialLine() { return initialLine; }
    public Map<String, List<String>> headers() { return headers; }
    /** Materialize a fresh byte array only when content is explicitly requested. */
    public byte[] content() { return content.bytes(); }
    public int contentBytes() { return content.size(); }
    public Map<String, List<String>> trailers() { return trailers; }
    public HttpReceiveState initialLineState() { return initialLineState; }
    public HttpReceiveState headerState() { return headerState; }
    public HttpReceiveState contentState() { return contentState; }
    public HttpReceiveState trailersState() { return trailersState; }
    public boolean complete() {
        return initialLineState == COMPLETE && headerState == COMPLETE
            && (contentState == COMPLETE || contentState == NOT_APPLICABLE)
            && (trailersState == COMPLETE || trailersState == NOT_APPLICABLE);
    }
    static HttpRequestSnapshot empty() {
        return new HttpRequestSnapshot("", Map.of(), new byte[0], Map.of(),
            NOT_REACHED, NOT_REACHED, NOT_REACHED, NOT_REACHED);
    }
}
