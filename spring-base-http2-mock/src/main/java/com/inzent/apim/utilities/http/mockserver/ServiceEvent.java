package com.inzent.apim.utilities.http.mockserver;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable metadata snapshot; exception references are diagnostic and must not be mutated. */
public record ServiceEvent(Instant timestamp, long registrationId, String mockCase,
        Long clientId, ServiceEventType type, InetSocketAddress localAddress,
        InetSocketAddress remoteAddress, Enum<?> point, int bytes, long totalBytes,
        TerminationReason reason, Throwable exception, boolean action,
        ResponseMetadata response, Map<String, Object> socketOptions) {
    public ServiceEvent {
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(mockCase);
        Objects.requireNonNull(type);
        socketOptions = Map.copyOf(socketOptions);
        ThrowableSnapshot snapshot = ThrowableSnapshot.capture(exception);
        exception = snapshot;
        if (response == null && action && snapshot != null) response = snapshot.response();
    }
    public record ResponseMetadata(int statusCode, String contentType, int contentLength) {}
}
