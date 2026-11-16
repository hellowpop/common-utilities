package com.inzent.apim.utilities.http.mockserver;

import java.time.Duration;

/** Immutable per-binding socket and mock behavior configuration. */
public record MockOptions(
        boolean reuseAddress,
        int backlog,
        Integer serverReceiveBufferSize,
        Integer receiveBufferSize,
        Integer sendBufferSize,
        int lingerSeconds,
        boolean keepAlive,
        boolean tcpNoDelay,
        int readTimeoutMillis,
        int readChunkSize,
        Duration readInterval,
        Duration responseTimeout) {
    public MockOptions {
        if (backlog <= 0 || readChunkSize <= 0 || readTimeoutMillis < 0)
            throw new IllegalArgumentException("backlog/readChunkSize must be positive and timeout nonnegative");
        if (lingerSeconds < -1 || lingerSeconds > 65535) throw new IllegalArgumentException("lingerSeconds must be -1..65535");
        positive(serverReceiveBufferSize, "serverReceiveBufferSize");
        positive(receiveBufferSize, "receiveBufferSize");
        positive(sendBufferSize, "sendBufferSize");
        Durations.nanos(readInterval, "readInterval", true);
        Durations.nanos(responseTimeout, "responseTimeout", false);
    }
    private static void positive(Integer value, String name) {
        if (value != null && value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
    public static Builder builder() { return new Builder(); }
    public static MockOptions defaults() { return builder().build(); }
    public static final class Builder {
        private boolean reuseAddress = true;
        private int backlog = 50;
        private Integer serverReceiveBufferSize = null;
        private Integer receiveBufferSize = null;
        private Integer sendBufferSize = null;
        private int lingerSeconds = -1;
        private boolean keepAlive = false;
        private boolean tcpNoDelay = true;
        private int readTimeoutMillis = 0;
        private int readChunkSize = 1024;
        private Duration readInterval = Duration.ofMillis(100);
        private Duration responseTimeout = Duration.ofSeconds(10);
        public Builder reuseAddress(boolean value) { reuseAddress = value; return this; }
        public Builder backlog(int value) { backlog = value; return this; }
        public Builder serverReceiveBufferSize(Integer value) { serverReceiveBufferSize = value; return this; }
        public Builder receiveBufferSize(Integer value) { receiveBufferSize = value; return this; }
        public Builder sendBufferSize(Integer value) { sendBufferSize = value; return this; }
        public Builder lingerSeconds(int value) { lingerSeconds = value; return this; }
        public Builder keepAlive(boolean value) { keepAlive = value; return this; }
        public Builder tcpNoDelay(boolean value) { tcpNoDelay = value; return this; }
        public Builder readTimeoutMillis(int value) { readTimeoutMillis = value; return this; }
        public Builder readChunkSize(int value) { readChunkSize = value; return this; }
        public Builder readInterval(Duration value) { readInterval = value; return this; }
        public Builder responseTimeout(Duration value) { responseTimeout = value; return this; }
        public MockOptions build() {
            return new MockOptions(reuseAddress, backlog, serverReceiveBufferSize, receiveBufferSize, sendBufferSize, lingerSeconds, keepAlive, tcpNoDelay, readTimeoutMillis, readChunkSize, readInterval, responseTimeout);
        }
    }
}
