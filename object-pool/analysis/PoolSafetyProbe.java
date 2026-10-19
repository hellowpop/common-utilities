package com.inzent.apim.utilities.pool.analysis;

import com.inzent.apim.utilities.pool.*;
import java.lang.reflect.Field;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Standalone analysis probe; does not change the implementation or depend on JUnit. */
public final class PoolSafetyProbe {
    private static int checks;
    private static final class Target {
        boolean closed;
        final byte[] payload = new byte[1024];
    }
    private static class Pool extends MainPool<Target> {
        int created, destroyed;
        boolean generateNpe, destroyNpe;
        Pool(int initial, int max, int cache) {
            super(new PoolConfig(initial, max, 4, 0, cache));
        }
        @Override protected Target generateTargetObject() {
            if (generateNpe) throw new NullPointerException("generate NPE");
            created++; return new Target();
        }
        @Override protected void destroyTargetObject(Target target) {
            destroyed++;
            if (destroyNpe) throw new NullPointerException("destroy NPE before release");
            target.closed = true;
        }
    }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getName());
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }

    private static void nullBoundaries() throws Exception {
        expect(NullPointerException.class, () -> new MainPool<Object>(null) {
            @Override protected Object generateTargetObject() { return new Object(); }
            @Override protected void destroyTargetObject(Object value) {}
        });
        try (var pool = new Pool(0, 1, 1)) {
            pool.start(); var sub = pool.borrowSubPool();
            expect(NullPointerException.class, () -> pool.recycleSubPool(null));
            expect(NullPointerException.class, () -> sub.recycleObjectHolder(null));
            check(pool.stats().createdObjectCount() == 0, "null arguments changed state");
            sub.close(); check(sub.stats() != null, "closed Sub stats null");
        }
        System.out.println("NULL_BOUNDARIES: expected NPE; no state corruption");
    }

    private static void hookNpe() throws Exception {
        try (var pool = new Pool(0, 1, 1)) {
            pool.start(); var sub = pool.borrowSubPool(); pool.generateNpe = true;
            expect(NullPointerException.class, sub::borrowObjectHolder);
            check(pool.stats().reservedCreationCount() == 0, "generation reservation leaked");
            check(pool.stats().createdObjectCount() == 0, "failed generation registered a target");
            pool.generateNpe = false; var holder = sub.borrowObjectHolder(); holder.close(); sub.close();
        }
        var pool = new Pool(3, 3, 3); pool.start(); pool.destroyNpe = true;
        expect(NullPointerException.class, pool::close);
        check(pool.destroyed == 3 && pool.stats().destroyFailureCount() == 3, "NPE stopped cleanup batch");
        check(pool.isTerminated() && pool.stats().createdObjectCount() == 0, "destroy NPE retained registry");
        pool.close(); check(pool.destroyed == 3, "destroy was retried");
        System.out.println("HOOK_NPE: generation reservation released; all 3 destroy attempts completed");
    }

    private static void normalReferencesAndMissedReturn() throws Exception {
        var pool = new Pool(0, 2, 2); pool.start(); var sub = pool.borrowSubPool();
        var a = sub.borrowObjectHolder(); var b = sub.borrowObjectHolder();
        a.close();
        check(field(a, "main") == null, "recycled Holder retains Main");
        sub.close();
        check(field(sub, "main") == pool, "pending return should preserve Main");
        pool.close();
        check(pool.stats().createdObjectCount() == 1 && !pool.isTerminated(), "missing Holder prematurely released");
        check(pool.stats().borrowedObjectCount() == 1, "missing Holder lost from registry");
        expect(IllegalStateException.class, b::get);
        b.close();
        check(pool.isTerminated(), "late Holder failed to terminate pool");
        check(field(b, "main") == null && field(sub, "main") == null, "completed handles retain Main");
        check(sub.stats().managedObjectCount() == 0, "completed Sub count not zero");
        System.out.println("REFERENCES: closed Sub retains Main only while Holder outstanding; final refs null");
    }

    private static void destroyFailureExternalResource() throws Exception {
        var pool = new Pool(0, 1, 1); pool.start(); var sub = pool.borrowSubPool();
        var holder = sub.borrowObjectHolder(); var target = holder.get();
        holder.close(); sub.close(); pool.destroyNpe = true;
        expect(NullPointerException.class, pool::close);
        check(pool.isTerminated() && !target.closed, "external release failure was hidden by fixture");
        check(pool.stats().destroyFailureCount() == 1, "destroy failure not recorded");
        System.out.println("EXTERNAL_RESOURCE: CLOSED/created=0 can coexist with failed target release");
    }

    private static void loggerFailure(boolean expectOriginal) throws Exception {
        // Default JDK System.Logger uses JUL. No private implementation mutation is used.
        Logger logger = Logger.getLogger(MainPool.class.getName());
        Level originalLevel = logger.getLevel(); boolean parents = logger.getUseParentHandlers();
        Handler failing = new Handler() {
            @Override public void publish(LogRecord record) { throw new NullPointerException("logger NPE"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var pool = new Pool(0, 2, 2); pool.start(); var sub = pool.borrowSubPool();
        var active = sub.borrowObjectHolder(); var idle = sub.borrowObjectHolder(); idle.close();
        try {
            logger.setUseParentHandlers(false); logger.setLevel(Level.ALL); logger.addHandler(failing);
            expect(NullPointerException.class, pool::close);
            check(expectOriginal
                            ? pool.stats().destroyingObjectCount() == 1 && pool.destroyed == 0
                            : pool.stats().destroyingObjectCount() == 0 && pool.destroyed == 1,
                    "unexpected cleanup state after logger failure");
        } finally {
            logger.removeHandler(failing); logger.setLevel(originalLevel); logger.setUseParentHandlers(parents);
        }
        active.close(); pool.close(); sub.close();
        check(pool.stats().createdObjectCount() == (expectOriginal ? 1 : 0), "unexpected retained count");
        check(expectOriginal
                        ? pool.stats().destroyingObjectCount() == 1 && !pool.isTerminated()
                        : pool.stats().destroyingObjectCount() == 0 && pool.isTerminated(),
                "unexpected terminal state");
        check(pool.destroyed == (expectOriginal ? 1 : 2), "unexpected destruction count");
        System.out.println((expectOriginal ? "LOGGER_FAILURE_REPRODUCED" : "LOGGER_FAILURE_FIXED") + ": state=" + pool.stats().state()
                + " created=" + pool.stats().createdObjectCount()
                + " destroying=" + pool.stats().destroyingObjectCount()
                + " destroyed=" + pool.destroyed);
    }

    public static void main(String[] args) throws Exception {
        nullBoundaries(); hookNpe(); normalReferencesAndMissedReturn();
        destroyFailureExternalResource();
        // Historical mode is for running against the original, unfixed classes only.
        loggerFailure(args.length == 1 && "--expect-original".equals(args[0]));
        System.out.println("ANALYSIS CHECKS PASSED: " + checks);
    }
}
