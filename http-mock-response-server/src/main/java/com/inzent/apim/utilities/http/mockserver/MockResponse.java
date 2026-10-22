package com.inzent.apim.utilities.http.mockserver;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** In-memory HTTP response; both constructor and body accessor copy the supplied bytes. */
public final class MockResponse {
    private final int statusCode;
    private final String contentType;
    private final byte[] body;
    public MockResponse(int statusCode, String contentType, byte[] body) {
        if (statusCode < 200 || statusCode > 599) throw new IllegalArgumentException("statusCode must be 200..599");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        if (contentType.isBlank() || contentType.chars().anyMatch(c -> c < 32 || c > 126))
            throw new IllegalArgumentException("contentType must contain printable ASCII without control characters");
        if ((statusCode == 204 || statusCode == 205 || statusCode == 304) && body.length != 0)
            throw new IllegalArgumentException("statusCode forbids a response body");
        this.statusCode = statusCode;
        this.contentType = contentType;
        this.body = body.clone();
    }
    public MockResponse(byte[] body) { this(200, "application/octet-stream", body); }
    public static MockResponse utf8(int statusCode, String contentType, String body) {
        return new MockResponse(statusCode, contentType, Objects.requireNonNull(body).getBytes(StandardCharsets.UTF_8));
    }
    public int statusCode() { return statusCode; }
    public String contentType() { return contentType; }
    public byte[] body() { return body.clone(); }
    public int contentLength() { return body.length; }
    void writeBody(java.io.OutputStream output) throws java.io.IOException { output.write(body); }
    ServiceEvent.ResponseMetadata metadata() {
        return new ServiceEvent.ResponseMetadata(statusCode, contentType, body.length);
    }
}
