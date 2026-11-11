package com.inzent.apim.utilities.http2mock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mock.execution", ignoreUnknownFields = false)
public record MockExecutionProperties(@DefaultValue("16") int maxThreadCount, @DefaultValue("4") int maxConcurrentJobs,
        @DefaultValue("100000") long maxTotalRequests, @DefaultValue("1073741824") long maxContentLength,
        @DefaultValue("65536") int responsePreviewBytes, @DefaultValue("64") int eventQueueCapacity,
        @DefaultValue("256") int maxSummaryGroups, @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout, @DefaultValue("15m") Duration jobTimeout,
        @DefaultValue("2s") Duration heartbeatInterval) {
    public MockExecutionProperties {
        if (maxThreadCount < 1 || maxConcurrentJobs < 1 || (long) maxThreadCount * maxConcurrentJobs > 256
                || maxTotalRequests < 1 || maxContentLength < 0 || responsePreviewBytes < 0 || responsePreviewBytes > 1_048_576
                || eventQueueCapacity < 1 || eventQueueCapacity > 1024 || maxSummaryGroups < 1 || maxSummaryGroups > 1024)
            throw new IllegalArgumentException("Invalid mock execution resource limits");
        for (Duration duration : new Duration[]{connectTimeout, requestTimeout, jobTimeout, heartbeatInterval}) {
            if (duration == null || duration.toMillis() < 1) throw new IllegalArgumentException("Execution timeouts must be positive milliseconds");
        }
        Math.addExact(jobTimeout.toMillis(), 30000L);
    }
}
