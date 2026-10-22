package com.inzent.apim.utilities.http.mockserver;

import java.time.Duration;

public record ServerOptions(int maxClients, Duration shutdownTimeout) {
    public ServerOptions {
        if (maxClients <= 0) throw new IllegalArgumentException("maxClients must be positive");
        Durations.nanos(shutdownTimeout, "shutdownTimeout", false);
    }
    public static ServerOptions defaults() { return new ServerOptions(64, Duration.ofSeconds(5)); }
}
