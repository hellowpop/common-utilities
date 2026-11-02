package com.inzent.apim.utilities.netty.http.mockserver.config;

import lombok.Builder;
import java.util.List;

/** Validated, immutable configuration shared across connections. */
@Builder(toBuilder = true)
public record ServerConfig(String host, int port, int bossThreads, int workerThreads,
        int backlog, boolean reuseAddress, boolean tcpNoDelay, boolean keepAlive,
        int maxConnections, int maxInitialLineLength, int maxHeaderSize, int maxContentLength,
        int requestTimeoutMs, int writeBufferLowWaterMark, int writeBufferHighWaterMark,
        int maxPendingResponses, int shutdownTimeoutMs, String headerName,
        String defaultAction, List<String> scanPackages) {
    public ServerConfig {
        if (host == null || host.isBlank()) throw invalid("server.host");
        if (port < 1 || port > 65535) throw invalid("server.port");
        if (bossThreads < 1 || workerThreads < 0 || backlog < 1) throw invalid("server threads/backlog");
        if (maxConnections < 1 || maxInitialLineLength < 1 || maxHeaderSize < 1 || maxContentLength < 1)
            throw invalid("server limits");
        if (requestTimeoutMs < 1 || shutdownTimeoutMs < 1 || maxPendingResponses < 1)
            throw invalid("server timeout/pending responses");
        if (writeBufferLowWaterMark < 0 || writeBufferHighWaterMark <= writeBufferLowWaterMark)
            throw invalid("server write buffer watermarks");
        if (headerName == null || !headerName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
            throw invalid("mock.header-name");
        if (defaultAction == null || !defaultAction.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw invalid("mock.default-action");
        if (scanPackages == null || scanPackages.isEmpty()) throw invalid("mock.scan-packages");
        scanPackages = List.copyOf(scanPackages);
        for (String name : scanPackages) {
            if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
                throw invalid("mock.scan-packages");
        }
    }
    private static IllegalArgumentException invalid(String path) {
        return new IllegalArgumentException("Invalid configuration: " + path);
    }
}
