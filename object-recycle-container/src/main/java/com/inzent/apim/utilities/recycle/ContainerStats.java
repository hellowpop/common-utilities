package com.inzent.apim.utilities.recycle;

/** Disjoint slot counters. total + creating is bounded by max. */
public record ContainerStats(String state, int initial, int min, int max, int total, int creating,
        int mainIdle, int subStack, int subReturns, int borrowed, int recycling, int quarantined,
        int destroying, int activeSubs, int revokedSubs, long destroyedCount, long failureCount) {
    public int accounted() { return total + creating; }
}
