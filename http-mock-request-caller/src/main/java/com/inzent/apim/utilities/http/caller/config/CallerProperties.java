package com.inzent.apim.utilities.http.caller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("caller")
public record CallerProperties(@DefaultValue("./data") String dataDirectory,
                               @DefaultValue("4") int maxConcurrentRuns,
                               @DefaultValue("64") int maxTotalWorkers,
                               @DefaultValue("67108864") long maxResponseBytes,
                               @DefaultValue("1000") long scriptTimeoutMillis,
                               @DefaultValue("1073741824") long runBodyBudget,
                               @DefaultValue("7") int retentionDays) {
    public CallerProperties {
        if(maxConcurrentRuns<1||maxConcurrentRuns>32||maxTotalWorkers<1||maxTotalWorkers>256||maxResponseBytes<1||scriptTimeoutMillis<1||runBodyBudget<1||retentionDays<1)throw new IllegalArgumentException("Invalid caller resource limits");
    }
}
