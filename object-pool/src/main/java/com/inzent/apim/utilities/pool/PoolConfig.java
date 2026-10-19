package com.inzent.apim.utilities.pool;

/** Immutable capacity limits. Initial Sub allocation is completed before publication. */
public record PoolConfig(int initialSize, int maxSize, int maxSubPools,
                         int initialObjectsPerSub, int maxCachedPerSub) {
    public PoolConfig {
        if (initialSize < 0 || maxSize < 1 || initialSize > maxSize || maxSubPools < 1
                || initialObjectsPerSub < 0 || initialObjectsPerSub > maxCachedPerSub
                || maxCachedPerSub < 0 || maxCachedPerSub > maxSize)
            throw new IllegalArgumentException("Invalid pool capacity or initial allocation");
    }
    public static PoolConfig defaults() { return new PoolConfig(0, 128, 32, 0, 8); }
}
