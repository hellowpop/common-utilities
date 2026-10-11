package com.inzent.apim.utilities.recycle;

/** One Sub borrowing lifetime. borrow = recycle + forcedClose + outstanding. */
public record SubStats(long id, String state, long borrowCount, long recycleCount,
        long forcedCloseCount, long outstandingCount, long pendingCleanupCount) {}
