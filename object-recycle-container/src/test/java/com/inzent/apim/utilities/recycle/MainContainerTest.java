package com.inzent.apim.utilities.recycle;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class MainContainerTest {
    static ContainerConfig config(int initial, int min, int max, int perSub) {
        return ContainerConfig.builder().initial(initial).min(min).max(max)
                .initialObjectsPerSub(perSub).build();
    }

    static class Pool extends MainContainer<StringBuilder> {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger recycled = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        boolean reusable = true;
        int failCreationAt = -1;
        boolean failRecycle, failDestroy;
        Pool(ContainerConfig config) { super(config); }
        Pool(ContainerConfig config, LeakListener listener, AtomicLong clock) {
            super(config, listener, clock::get);
        }
        @Override protected ObjectHolder<StringBuilder> createNewHolder() {
            if (created.incrementAndGet() == failCreationAt) throw new IllegalArgumentException("creation failure");
            return new ObjectHolder<>(new StringBuilder());
        }
        @Override protected boolean isRecycleable(ObjectHolder<StringBuilder> holder) { return reusable; }
        @Override protected void onRecycle(ObjectHolder<StringBuilder> holder) {
            recycled.incrementAndGet();
            if (failRecycle) throw new IllegalArgumentException("reset failure");
            holder.value().setLength(0);
        }
        @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) {
            destroyed.incrementAndGet();
            if (failDestroy) throw new IllegalArgumentException("destroy failure");
        }
    }

    @Test void startIsExplicitAndReturnsStayInTheirSubUntilSubCloses() {
        try (var pool = new Pool(config(2, 0, 4, 2))) {
            assertEquals(0, pool.created.get());
            assertThrows(IllegalStateException.class, pool::borrowContainer);
            pool.start(); pool.start();
            assertEquals(2, pool.created.get());
            var sub = pool.borrowContainer();
            var a = sub.borrowObjectHolder();
            var b = sub.borrowObjectHolder();
            var values = new ArrayList<StringBuilder>();
            a.use(v -> { v.append("dirty"); values.add(v); });
            b.use(values::add);
            assertTrue(a.recycle()); assertTrue(b.recycle());
            assertEquals(0, pool.stats().mainIdle());
            var next = sub.borrowObjectHolder();
            next.use(v -> { assertSame(values.get(1), v); assertEquals(0, v.length()); });
            assertFalse(b.recycle());
            assertThrows(IllegalStateException.class, () -> b.use(v -> fail()));
            next.close();
            assertEquals(3, sub.stats().borrowCount());
            assertEquals(3, sub.stats().recycleCount());
            sub.close(); sub.close();
            assertEquals(2, pool.stats().mainIdle());
            assertEquals(3, pool.recycled.get());
        }
    }

    @Test void workerReturnUsesInboxAndRejectsDirectSubCalls() throws Exception {
        try (var pool = new Pool(config(1, 0, 1, 1)); var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            executor.submit(() -> {
                assertThrows(IllegalStateException.class, sub::borrowObjectHolder);
                lease.use(v -> v.append("worker")); lease.close(); lease.close();
            }).get(5, TimeUnit.SECONDS);
            assertEquals(1, pool.stats().subReturns());
            assertEquals(0, pool.stats().mainIdle());
            var next = sub.borrowObjectHolder();
            next.use(v -> assertEquals(0, v.length())); next.close(); sub.close();
            assertEquals(1, pool.stats().mainIdle());
        }
    }

    @Test void exhaustionAndWrongOwnerDoNotChangeCounters() {
        try (var pool = new Pool(config(0, 0, 1, 0))) {
            pool.start(); var a = pool.borrowContainer(); var b = pool.borrowContainer();
            var lease = a.borrowObjectHolder();
            assertThrows(PoolExhaustedException.class, b::borrowObjectHolder);
            assertThrows(IllegalArgumentException.class, () -> b.recycleObjectHolder(lease));
            assertEquals(0, b.stats().recycleCount());
            lease.close(); a.close(); b.close();
        }
    }

    @Test void holderExpiryUsesInclusiveBoundaryAndOldEventCannotCloseNewLease() {
        var clock = new AtomicLong(); var events = new ArrayList<LeakEvent>();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(2).initialObjectsPerSub(1)
                .holderIdleLimit(Duration.ofNanos(10)).build();
        try (var pool = new Pool(cfg, events::add, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var old = sub.borrowObjectHolder();
            clock.set(9); assertEquals(0, pool.patrol().detected());
            old.touch(); clock.set(19); assertEquals(1, pool.patrol().detected());
            assertEquals(0, pool.destroyed.get());
            assertTrue(old.recycle()); var next = sub.borrowObjectHolder();
            assertFalse(events.getFirst().forceClose()); next.use(v -> v.append("valid"));
            next.close(); sub.close();
        }
    }

    @Test void defaultListenerRevokesUseBeforeDeferredDestruction() throws Exception {
        var clock = new AtomicLong(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(10)).build();
        try (var pool = new Pool(cfg, null, clock); var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            var task = executor.submit(() -> lease.use(v -> { entered.countDown(); await(release); }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); clock.set(10);
                assertEquals(1, pool.patrol().detected());
                assertEquals(0, pool.destroyed.get()); assertEquals(1, pool.stats().quarantined());
                assertThrows(IllegalStateException.class, lease::touch);
                assertThrows(PoolExhaustedException.class, sub::borrowObjectHolder);
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS); assertEquals(1, pool.destroyed.get());
            assertFalse(lease.recycle()); assertEquals(1, sub.stats().forcedCloseCount()); sub.close();
        }
    }

    @Test void returnDuringUseCleansOnlyAfterUseAndConcurrentUseIsRejected() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(1, 0, 1, 1)); var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            var task = executor.submit(() -> lease.use(v -> { entered.countDown(); await(release); }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> lease.use(v -> fail()));
                assertTrue(lease.recycle()); assertEquals(0, pool.recycled.get());
                assertEquals(1, sub.stats().pendingCleanupCount());
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS); assertEquals(1, pool.recycled.get());
            sub.close(); assertEquals(1, pool.stats().mainIdle());
        }
    }

    @Test void useFailureAndNonReusableHolderAreDestroyedExactlyOnce() {
        try (var pool = new Pool(config(1, 0, 2, 1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            var failure = new IllegalArgumentException("callback");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> lease.use(v -> { throw failure; })));
            assertEquals(1, pool.destroyed.get()); assertFalse(lease.recycle());
            pool.reusable = false; var next = sub.borrowObjectHolder();
            assertTrue(next.recycle()); assertEquals(2, pool.destroyed.get());
            assertEquals(0, pool.recycled.get()); sub.close();
        }
    }

    @Test void partialStartupFailureClosesPoolAndDestroysCreatedHolders() {
        var pool = new Pool(config(3, 0, 3, 0)); pool.failCreationAt = 2;
        var error = assertThrows(ObjectCreationException.class, pool::start);
        assertEquals("creation failure", error.getCause().getMessage());
        assertEquals(1, pool.destroyed.get()); assertEquals(0, pool.stats().total());
        assertEquals(0, pool.stats().creating());
        assertThrows(IllegalStateException.class, pool::start); pool.close();
    }

    @Test void patrolReportsOnlyTheCurrentScansForcedClosures() {
        var clock = new AtomicLong();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(2).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(10)).build();
        try (var pool = new Pool(cfg, null, clock)) {
            pool.start(); var sub = pool.borrowContainer(); sub.borrowObjectHolder();
            clock.set(10); assertEquals(1, pool.patrol().forcedClosed());
            assertEquals(0, pool.patrol().forcedClosed()); sub.close();
        }
    }

    @Test void mainCanRevokeSubFromAnotherThreadWithoutAccessingItsStack() throws Exception {
        try (var pool = new Pool(config(1, 0, 1, 1)); var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertTrue(executor.submit(() -> pool.recycleContainer(sub)).get(5, TimeUnit.SECONDS));
            assertFalse(lease.recycle()); assertThrows(IllegalStateException.class, sub::borrowObjectHolder);
            sub.close(); assertEquals(1, pool.destroyed.get());
        }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
