package com.inzent.apim.utilities.http2mock;

import java.util.*;

final class MockExecutionSummary {
    private long completed, success, bytes, ungrouped;
    private double elapsedSum, minimum = Double.POSITIVE_INFINITY, maximum;
    private final Map<String,Long> statuses = new TreeMap<>(), errors = new TreeMap<>();
    private final Set<Key> groups = new HashSet<>();
    private final int maxGroups;
    private record Key(Integer status, String hash, boolean complete, String error) { }
    MockExecutionSummary(int maxGroups) { this.maxGroups = maxGroups; }
    synchronized void add(MockHttpTransport.Result result) {
        completed++; if (result.success()) success++;
        bytes += result.bodyBytes(); elapsedSum += result.elapsedMillis();
        minimum = Math.min(minimum, result.elapsedMillis()); maximum = Math.max(maximum, result.elapsedMillis());
        if (result.status() != null) statuses.merge(result.status().toString(), 1L, Long::sum);
        if (result.error() != null) errors.merge(result.error().split(":", 2)[0], 1L, Long::sum);
        var key = new Key(result.status(), result.sha256(), result.bodyComplete(), result.error());
        if (!groups.contains(key)) {
            if (groups.size() < maxGroups) groups.add(key); else ungrouped++;
        }
    }
    synchronized Map<String,Object> snapshot(String jobId, long total, long attempted, long delivered, long started,
                                            boolean cancelled, String reason) {
        var summary = new LinkedHashMap<String,Object>();
        summary.put("jobId", jobId); summary.put("totalCount", total); summary.put("attemptedCount", attempted);
        summary.put("completedCount", completed); summary.put("deliveredCount", delivered);
        summary.put("successCount", success); summary.put("failureCount", completed - success);
        summary.put("remainingCount", total - completed); summary.put("cancelled", cancelled); summary.put("cancellationReason", reason);
        summary.put("httpStatusCounts", Map.copyOf(statuses)); summary.put("errorCounts", Map.copyOf(errors));
        summary.put("totalResponseBytes", bytes); summary.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000.0);
        summary.put("averageResponseMillis", completed == 0 ? 0.0 : elapsedSum / completed);
        summary.put("minResponseMillis", completed == 0 ? 0.0 : minimum); summary.put("maxResponseMillis", maximum);
        summary.put("ungroupedResponseCount", ungrouped);
        return summary;
    }
}
