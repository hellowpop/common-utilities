package com.inzent.apim.utilities.recycle;

import java.time.Duration;

/** Immutable capacity and timeout policy. A null limit disables that timeout. */
public record ContainerConfig(int initial, int min, int max, int initialObjectsPerSub,
        int maxSubContainers, Duration poolIdleLimit, Duration subBorrowLimit,
        Duration subIdleLimit, Duration holderBorrowLimit, Duration holderIdleLimit,
        Duration patrolInterval, boolean automaticPatrol, boolean captureBorrowStack) {
    public ContainerConfig {
        if (min < 0 || initial < min || max < initial || max <= 0)
            throw new IllegalArgumentException("Require 0 <= min <= initial <= max and max > 0");
        if (initialObjectsPerSub < 0 || initialObjectsPerSub > max || maxSubContainers <= 0)
            throw new IllegalArgumentException("Invalid Sub capacity");
        validate(poolIdleLimit); validate(subBorrowLimit); validate(subIdleLimit);
        validate(holderBorrowLimit); validate(holderIdleLimit); validate(patrolInterval);
        if (patrolInterval == null) throw new IllegalArgumentException("patrolInterval is required");
    }
    private static void validate(Duration duration) {
        if (duration == null) return;
        try {
            if (duration.toNanos() <= 0) throw new IllegalArgumentException("Duration must be positive");
        } catch (ArithmeticException e) { throw new IllegalArgumentException("Duration exceeds nanos range", e); }
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private int initial = 16, min = 8, max = 1024, perSub = 8, maxSubs = 128;
        private Duration poolIdle, subBorrow, subIdle, holderBorrow, holderIdle;
        private Duration interval = Duration.ofSeconds(1);
        private boolean automatic, capture;
        public Builder initial(int v) { initial = v; return this; }
        public Builder min(int v) { min = v; return this; }
        public Builder max(int v) { max = v; return this; }
        public Builder initialObjectsPerSub(int v) { perSub = v; return this; }
        public Builder maxSubContainers(int v) { maxSubs = v; return this; }
        public Builder poolIdleLimit(Duration v) { poolIdle = v; return this; }
        public Builder subBorrowLimit(Duration v) { subBorrow = v; return this; }
        public Builder subIdleLimit(Duration v) { subIdle = v; return this; }
        public Builder holderBorrowLimit(Duration v) { holderBorrow = v; return this; }
        public Builder holderIdleLimit(Duration v) { holderIdle = v; return this; }
        public Builder patrolInterval(Duration v) { interval = v; return this; }
        public Builder automaticPatrol(boolean v) { automatic = v; return this; }
        public Builder captureBorrowStack(boolean v) { capture = v; return this; }
        public ContainerConfig build() {
            return new ContainerConfig(initial, min, max, perSub, maxSubs, poolIdle, subBorrow,
                    subIdle, holderBorrow, holderIdle, interval, automatic, capture);
        }
    }
}
