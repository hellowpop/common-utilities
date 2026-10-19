package com.inzent.apim.utilities.pool;

/** Atomic snapshot. createdObjectCount is current ownership, not a lifetime total. */
public record PoolStats(PoolState state, int createdObjectCount, int idleObjectCount,
        int activeSubPoolCount, int totalIdleObjectCount, int preparingSubPoolCount,
        int closedSubPoolCount, int borrowedObjectCount, int returningObjectCount,
        int destroyingObjectCount, int reservedCreationCount, long totalCreatedObjectCount,
        long creationFailureCount, long destroyAttemptCount, long destroyFailureCount,
        long subBorrowCount, long subRecycleCount, long holderBorrowCount, long holderRecycleCount) {}
