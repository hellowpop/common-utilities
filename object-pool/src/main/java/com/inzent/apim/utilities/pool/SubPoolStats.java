package com.inzent.apim.utilities.pool;

/** Initial count stays fixed; managed count includes outstanding borrowed objects. */
public record SubPoolStats(long id, boolean closed, int initialObjectCount,
        int managedObjectCount, int idleObjectCount, int borrowedCount, int returningCount,
        long holderBorrowCount, long holderRecycleCount) {}
