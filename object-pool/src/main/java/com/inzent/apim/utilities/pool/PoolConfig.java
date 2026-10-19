package com.inzent.apim.utilities.pool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Immutable settings. maxSize caps Main idle retention; Sub cache capacity is independent. */
public record PoolConfig(int initialSize, int maxSize, int maxSubPools,
                         int initialObjectsPerSub, int maxCachedPerSub) {
    public PoolConfig {
        if (initialSize < 0 || maxSize < 1 || initialSize > maxSize || maxSubPools < 1
                || initialObjectsPerSub < 0 || initialObjectsPerSub > maxCachedPerSub
                || maxCachedPerSub < 0)
            throw new IllegalArgumentException("Invalid pool capacity or initial allocation");
    }
    public static PoolConfig defaults() { return new PoolConfig(0, 128, 32, 0, 8); }

    /** Jackson property creator: omitted or null fields use defaults, then all limits are validated. */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static PoolConfig fromJson(
            @JsonProperty("initialSize") Integer initialSize,
            @JsonProperty("maxSize") Integer maxSize,
            @JsonProperty("maxSubPools") Integer maxSubPools,
            @JsonProperty("initialObjectsPerSub") Integer initialObjectsPerSub,
            @JsonProperty("maxCachedPerSub") Integer maxCachedPerSub) {
        PoolConfig defaults = defaults();
        return new PoolConfig(
                initialSize == null ? defaults.initialSize() : initialSize,
                maxSize == null ? defaults.maxSize() : maxSize,
                maxSubPools == null ? defaults.maxSubPools() : maxSubPools,
                initialObjectsPerSub == null ? defaults.initialObjectsPerSub() : initialObjectsPerSub,
                maxCachedPerSub == null ? defaults.maxCachedPerSub() : maxCachedPerSub);
    }
}
