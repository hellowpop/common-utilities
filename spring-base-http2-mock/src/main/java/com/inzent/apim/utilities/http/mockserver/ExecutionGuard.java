package com.inzent.apim.utilities.http.mockserver;

/** Prevents lifecycle calls from callbacks/worker points, including cross-executor calls. */
final class ExecutionGuard {
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();
    static void enter() { Integer depth = DEPTH.get(); DEPTH.set(depth == null ? 1 : depth + 1); }
    static void exit() {
        int depth = DEPTH.get() - 1;
        if (depth == 0) DEPTH.remove(); else DEPTH.set(depth);
    }
    static void requireCaller() {
        if (DEPTH.get() != null) throw new IllegalStateException("Lifecycle calls from a mock worker/listener are not allowed");
    }
    private ExecutionGuard() {}
}
