package com.inzent.apim.utilities.recycle;

import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.inzent.apim.utilities.recycle.MainContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSafetyTest {
    @Test void checkedCallbackFailureAlsoDestroysPayloadAndPreservesItsCause() {
        var use = new Exception("checked callback failure"); var destroy = new AssertionError("destroy");
        try (var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) { destroyed.incrementAndGet(); throw destroy; }
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertSame(use, assertThrows(Exception.class,
                    () -> lease.use(v -> ContainerSafetyTest.<RuntimeException>throwUnchecked(use))));
            assertEquals(0, pool.stats().total()); assertEquals(1, pool.destroyed.get());
            assertArrayEquals(new Throwable[]{destroy}, use.getSuppressed()); sub.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable failure) throws E { throw (E) failure; }

    @Test void releasedHandlesRemainSafeAfterTheirWeakMainReferenceClears() throws Exception {
        var pool = new Pool(config(1, 0, 1, 1)); pool.start();
        var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
        assertTrue(reaches(lease, pool)); assertTrue(reaches(sub, pool));
        lease.close(); sub.close(); var expectedStats = sub.stats(); pool.close();
        clearWeakReferences(lease); clearWeakReferences(sub);
        assertFalse(lease.recycle()); assertDoesNotThrow(lease::close);
        assertThrows(IllegalStateException.class, lease::touch);
        assertThrows(IllegalStateException.class, () -> lease.use(v -> fail("expired lease")));
        assertEquals(expectedStats, sub.stats()); assertDoesNotThrow(sub::close);
        assertThrows(IllegalStateException.class, sub::borrowObjectHolder);
        assertThrows(IllegalStateException.class, sub::patrol);
    }

    // Model weak-reference clearing deterministically, without depending on System.gc().
    private static void clearWeakReferences(Object value) throws Exception {
        for (var field : value.getClass().getDeclaredFields()) {
            if (Reference.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true); ((Reference<?>) field.get(value)).clear();
            }
        }
    }

    @Test void fatalDestroyErrorIsNotHiddenBehindEarlierNonfatalError() {
        var first = new AssertionError("first"); var fatal = new OutOfMemoryError("injected, not actual exhaustion");
        var clock = new AtomicLong();
        var cfg = ContainerConfig.builder().initial(3).min(0).max(3).initialObjectsPerSub(3)
                .subBorrowLimit(Duration.ofNanos(1)).build();
        try (var pool = new Pool(cfg, null, clock) {
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) {
                int call = destroyed.incrementAndGet();
                if (call == 1) throw first;
                if (call == 2) throw fatal;
            }
        }) {
            pool.start(); var sub = pool.borrowContainer(); clock.set(1);
            assertSame(fatal, assertThrows(OutOfMemoryError.class, pool::patrol));
            assertArrayEquals(new Throwable[]{first}, fatal.getSuppressed());
            assertEquals(3, pool.destroyed.get()); assertEquals(0, pool.stats().total()); sub.close();
        }
    }

    @Test void useFailureRemainsPrimaryWhenDestroyAlsoFails() {
        var use = new IllegalArgumentException("use"); var destroy = new AssertionError("destroy");
        try (var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) { throw destroy; }
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertSame(use, assertThrows(IllegalArgumentException.class, () -> lease.use(v -> { throw use; })));
            assertArrayEquals(new Throwable[]{destroy}, use.getSuppressed());
            assertEquals(0, pool.stats().total()); sub.close();
        }
    }

    @Test void initializationFailureRemainsPrimaryWhenRollbackDestroyFails() {
        var prepare = new IllegalArgumentException("prepare"); var destroy = new AssertionError("destroy");
        try (var pool = new Pool(config(2, 0, 2, 2)) {
            @Override protected SubContainer<StringBuilder> createSubContainer() {
                return new SubContainer<>(this) { @Override protected void onBorrow() { throw prepare; } };
            }
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) { destroyed.incrementAndGet(); throw destroy; }
        }) {
            pool.start(); assertSame(prepare, assertThrows(IllegalArgumentException.class, pool::borrowContainer));
            assertArrayEquals(new Throwable[]{destroy}, prepare.getSuppressed());
            assertEquals(2, pool.destroyed.get()); assertEquals(0, pool.stats().total());
            assertEquals(0, pool.stats().revokedSubs());
        }
    }

    @Test void partialCreationFailureRetainsItsCauseWhenRollbackDestroyFails() {
        var creation = new IllegalArgumentException("create"); var destroy = new AssertionError("destroy");
        var pool = new Pool(config(2, 0, 2, 0)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() {
                if (created.incrementAndGet() == 2) throw creation;
                return new ObjectHolder<>(new StringBuilder());
            }
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) { throw destroy; }
        };
        var failure = assertThrows(ObjectCreationException.class, pool::start);
        assertSame(creation, failure.getCause()); assertArrayEquals(new Throwable[]{destroy}, failure.getSuppressed());
        assertEquals("CLOSED", pool.stats().state()); assertEquals(0, pool.stats().accounted()); pool.close();
    }

    @Test void predicateErrorDestroysSlotBeforePropagating() {
        var failure = new AssertionError("predicate");
        try (var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected boolean isRecycleable(ObjectHolder<StringBuilder> holder) { throw failure; }
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertSame(failure, assertThrows(AssertionError.class, lease::recycle));
            assertEquals(0, pool.stats().total()); assertEquals(1, pool.destroyed.get());
            assertEquals(1, pool.stats().failureCount()); sub.close();
        }
    }

    @Test void recycleErrorAndDestroyErrorPreserveBothFailuresAndReleaseCapacity() {
        var recycle = new AssertionError("recycle"); var destroy = new AssertionError("destroy");
        try (var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected void onRecycle(ObjectHolder<StringBuilder> holder) { throw recycle; }
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) { throw destroy; }
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertSame(recycle, assertThrows(AssertionError.class, lease::close));
            assertArrayEquals(new Throwable[]{destroy}, recycle.getSuppressed());
            assertEquals(0, pool.stats().total()); assertEquals(2, pool.stats().failureCount()); sub.close();
        }
    }

    @Test void destroyErrorDoesNotAbandonOtherClaimedSlots() {
        var failure = new AssertionError("destroy");
        var pool = new Pool(config(3, 0, 3, 0)) {
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) {
                if (destroyed.incrementAndGet() == 1) throw failure;
            }
        };
        pool.start(); assertSame(failure, assertThrows(AssertionError.class, pool::close));
        assertEquals(3, pool.destroyed.get()); assertEquals(0, pool.stats().total());
        assertEquals(1, pool.stats().failureCount()); assertDoesNotThrow(pool::close);
    }

    @Test void returnedLeaseDoesNotKeepPoolPayloadOrOwnerThreadAlive() throws Exception {
        try (var pool = new Pool(config(1, 0, 1, 1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            Object[] payload = new Object[1]; lease.use(v -> payload[0] = v);
            long id = lease.id(), generation = lease.generation(); lease.close();
            assertFalse(reaches(lease, pool)); assertFalse(reaches(lease, payload[0]));
            assertFalse(reaches(lease, Thread.currentThread()));
            assertEquals(id, lease.id()); assertEquals(generation, lease.generation());
            assertFalse(lease.recycle()); assertThrows(IllegalStateException.class, lease::touch);
            var next = sub.borrowObjectHolder(); assertFalse(lease.recycle());
            next.use(v -> assertSame(payload[0], v)); next.close(); sub.close();
        }
    }

    @Test void revokedSubTokensDoNotKeepPoolOrDestroyedPayloadAlive() throws Exception {
        var pool = new Pool(config(1, 0, 1, 1)); pool.start(); var sub = pool.borrowContainer();
        var lease = sub.borrowObjectHolder(); Object[] payload = new Object[1]; lease.use(v -> payload[0] = v);
        lease.close(); pool.recycleContainer(sub); pool.close();
        assertFalse(reaches(sub, payload[0])); assertFalse(reaches(sub, pool));
        assertFalse(reaches(sub, Thread.currentThread()));
        assertEquals("RELEASED", sub.stats().state()); assertDoesNotThrow(sub::close);
    }

    @Test void retainedEventsDoNotKeepPoolOrPayloadAliveAndStillRevokeCurrentLease() throws Exception {
        var clock = new AtomicLong(); var events = new ArrayList<LeakEvent>();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(1)).subBorrowLimit(Duration.ofNanos(1)).build();
        try (var pool = new Pool(cfg, events::add, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            Object[] payload = new Object[1]; lease.use(v -> payload[0] = v); clock.set(1); pool.patrol();
            assertEquals(2, events.size());
            for (var event : events) {
                assertFalse(reaches(event, pool)); assertFalse(reaches(event, payload[0]));
            }
            assertTrue(events.get(1).forceClose()); assertFalse(events.get(1).forceClose());
            assertFalse(lease.recycle()); assertTrue(events.get(0).forceClose()); sub.close();
        }
    }

    @Test void automaticPatrolContinuesAfterListenerAssertionError() throws Exception {
        var calledTwice = new CountDownLatch(2);
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(1)).automaticPatrol(true)
                .patrolInterval(Duration.ofMillis(1)).build();
        try (var pool = new MainContainer<StringBuilder>(cfg, event -> {
            calledTwice.countDown(); throw new AssertionError("listener");
        }) {
            protected ObjectHolder<StringBuilder> createNewHolder() { return new ObjectHolder<>(new StringBuilder()); }
            protected boolean isRecycleable(ObjectHolder<StringBuilder> h) { return true; }
            protected void onRecycle(ObjectHolder<StringBuilder> h) {}
            protected void onDestroy(ObjectHolder<StringBuilder> h) {}
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            try { assertTrue(calledTwice.await(5, TimeUnit.SECONDS)); }
            finally { lease.close(); sub.close(); }
        }
    }

    @Test void nullCallbackKeepsLeaseReturnableAndHookNpeDestroysIt() {
        try (var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected void onRecycle(ObjectHolder<StringBuilder> holder) { throw new NullPointerException("reset"); }
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertThrows(NullPointerException.class, () -> lease.use(null));
            assertEquals(1, pool.stats().borrowed()); lease.close();
            assertEquals(0, pool.stats().total()); assertEquals(1, pool.stats().failureCount()); sub.close();
        }
    }

    /** Examine strong ownership, excluding weak references; no GC timing assumptions. */
    private static boolean reaches(Object root, Object target) throws Exception {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var queue = new ArrayDeque<Object>(); queue.add(root);
        while (!queue.isEmpty()) {
            Object value = queue.removeFirst();
            if (value == target) return true;
            if (!seen.add(value) || value instanceof Reference<?>) continue;
            if (value instanceof Map<?, ?> map) {
                for (var entry : map.entrySet()) { add(queue, entry.getKey()); add(queue, entry.getValue()); }
            } else if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) add(queue, item);
            } else if (value instanceof Object[] array) {
                for (Object item : array) add(queue, item);
            } else {
                for (Class<?> type = value.getClass(); type != null && type.getPackageName().startsWith("com.inzent.apim.utilities"); type = type.getSuperclass()) {
                    for (var field : type.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                            field.setAccessible(true); add(queue, field.get(value));
                        }
                    }
                }
            }
        }
        return false;
    }
    private static void add(ArrayDeque<Object> queue, Object value) { if (value != null) queue.add(value); }
}
