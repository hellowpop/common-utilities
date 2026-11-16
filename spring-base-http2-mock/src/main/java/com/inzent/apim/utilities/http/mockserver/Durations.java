package com.inzent.apim.utilities.http.mockserver;

import java.time.Duration;
import java.util.Objects;

final class Durations {
    private Durations() {}
    static long nanos(Duration value, String name, boolean allowZero) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || (!allowZero && value.isZero()))
            throw new IllegalArgumentException(name + " must be " + (allowZero ? "nonnegative" : "positive"));
        try { return value.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException(name + " is too large", overflow); }
    }
}
