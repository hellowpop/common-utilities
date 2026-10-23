package com.inzent.apim.utilities.http.mockserver;

/** Per-request byte limits; headerBytes also limits the separate trailer section. */
public record HttpProtocolLimits(int lineBytes, int headerBytes, int contentBytes) {
    public HttpProtocolLimits {
        if (lineBytes <= 0 || headerBytes <= 0 || contentBytes < 0)
            throw new IllegalArgumentException("line/header limits must be positive and content limit nonnegative");
    }
    public static HttpProtocolLimits defaults() {
        return new HttpProtocolLimits(8192, 65536, 16 * 1024 * 1024);
    }
}
