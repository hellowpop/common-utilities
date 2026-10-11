package com.inzent.apim.utilities.recycle;

/** Results of one best-effort scan; cleanup counters include completed maintenance. */
public record PatrolResult(int examined, int detected, int forcedClosed, int quarantined,
        long cleaned, long failures, boolean skipped) {}
