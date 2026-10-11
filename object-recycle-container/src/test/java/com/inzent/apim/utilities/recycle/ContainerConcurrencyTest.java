package com.inzent.apim.utilities.recycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import static com.inzent.apim.utilities.recycle.MainContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ContainerConcurrencyTest {
    @Test void replenishmentFailureCannotDestroyAnAlreadyBorrowedFreshHolder() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var logs = new ContainerLifecycleTest.Logs(); var pool = new Pool(config(2, 2, 2, 0)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() {
                if (created.incrementAndGet() == 4) {
                    entered.countDown(); await(release); throw new IllegalArgumentException("partial replenish");
                }
                return new ObjectHolder<>(new StringBuilder());
            }
        }; var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var original = pool.borrowContainer(); original.borrowObjectHolder(); original.borrowObjectHolder();
            var revoked = executor.submit(() -> pool.recycleContainer(original));
            SubContainer<StringBuilder> currentSub;
            HolderLease<StringBuilder> current;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); currentSub = pool.borrowContainer();
                current = currentSub.borrowObjectHolder();
            } finally { release.countDown(); }
            assertTrue(revoked.get(5, TimeUnit.SECONDS));
            current.use(v -> v.append("new owner")); assertEquals(0, currentSub.stats().forcedCloseCount());
            assertEquals(2, pool.destroyed.get()); assertEquals(0, pool.stats().creating());
            current.close(); currentSub.close(); original.close();
        }
    }

    @Test void failedAllocationRollbackCannotDestroyAReassignedSlot() throws Exception {
        var selected = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var originalSub = new AtomicReference<SubContainer<StringBuilder>>();
        var ready = new CountDownLatch(1); var allocate = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        try (var pool = new Pool(config(1, 0, 1, 0)); var executor = Executors.newSingleThreadExecutor()) {
            // Pause after allocation's reservation transaction, without adding test hooks to production.
            var field = MainContainer.class.getDeclaredField("lock"); field.setAccessible(true);
            field.set(pool, new ReentrantLock() {
                @Override public void unlock() {
                    super.unlock();
                    if (!Thread.currentThread().getName().startsWith("pool-")) return;
                    boolean inAllocation = Arrays.stream(Thread.currentThread().getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals(MainContainer.class.getName())
                                    && frame.getMethodName().equals("allocate"));
                    if (inAllocation && paused.compareAndSet(false, true)) {
                        selected.countDown(); await(resume);
                    }
                }
            });
            pool.start();
            // initialObjectsPerSub=0 must not trigger the pause during Sub preparation.
            paused.set(true);
            var task = executor.submit(() -> {
                var sub = pool.borrowContainer(); originalSub.set(sub); ready.countDown(); await(allocate);
                assertThrows(IllegalStateException.class, () -> pool.newObject(sub)); sub.close();
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); paused.set(false); allocate.countDown();
            HolderLease<StringBuilder> current;
            SubContainer<StringBuilder> newSub;
            try {
                assertTrue(selected.await(5, TimeUnit.SECONDS)); assertTrue(pool.recycleContainer(originalSub.get()));
                newSub = pool.borrowContainer(); current = newSub.borrowObjectHolder();
            } finally { resume.countDown(); }
            task.get(5, TimeUnit.SECONDS);
            current.use(v -> v.append("new owner"));
            assertEquals(0, newSub.stats().forcedCloseCount()); assertEquals(0, pool.destroyed.get());
            current.close(); newSub.close();
        }
    }

    @Test void closeDuringCreationRetainsReservationAndDestroysLateFactoryResult() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(0, 0, 1, 1)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() {
                entered.countDown(); await(release); return super.createNewHolder();
            }
        }; var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var task = executor.submit(pool::borrowContainer);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, pool.stats().creating()); pool.close();
                assertEquals(1, pool.stats().creating()); assertEquals(1, pool.stats().revokedSubs());
                assertThrows(IllegalStateException.class, pool::borrowContainer);
            } finally { release.countDown(); }
            var error = assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals(1, pool.destroyed.get()); assertEmpty(pool.stats());
        }
    }

    @Test void closeDuringStartupDestroysLateHoldersAndNeverOpens() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(2, 0, 2, 0)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() {
                entered.countDown(); await(release); return super.createNewHolder();
            }
        }; var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(pool::start);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, pool::start);
                pool.close(); assertEquals(2, pool.stats().creating());
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(1, pool.created.get()); assertEquals(1, pool.destroyed.get());
            assertEquals("CLOSED", pool.stats().state()); assertEmpty(pool.stats());
        }
    }

    @Test void mainCloseDuringUseReturnsBeforeCallbackAndDefersDestroy() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var logs = new ContainerLifecycleTest.Logs(); var pool = new Pool(config(1, 0, 1, 1));
             var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            var task = executor.submit(() -> lease.use(v -> { entered.countDown(); await(release); }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); pool.close(); pool.close();
                assertEquals(1, pool.stats().quarantined()); assertEquals(0, pool.destroyed.get());
                assertFalse(lease.recycle()); assertThrows(IllegalStateException.class, lease::touch);
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS); assertEquals(1, pool.destroyed.get()); assertEmpty(pool.stats()); sub.close();
        }
    }

    @Test void subNormalCloseAfterAcceptedReturnWaitsForUseThenReturnsToMain() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(1, 0, 1, 1)); var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            var task = executor.submit(() -> lease.use(v -> { entered.countDown(); await(release); }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); lease.close(); sub.close();
                assertEquals(1, pool.stats().recycling()); assertEquals(1, pool.stats().revokedSubs());
                assertThrows(PoolExhaustedException.class, pool::borrowContainer);
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS); assertEquals(1, pool.recycled.get());
            assertEquals(0, pool.destroyed.get()); assertEquals(1, pool.stats().mainIdle());
            assertEquals(0, pool.stats().revokedSubs()); pool.borrowContainer().close();
        }
    }

    @Test void mainCloseDuringRecycleDoesNotOverlapDestroyAndPreventsPublication() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var recyclingNow = new AtomicBoolean(); var overlap = new AtomicBoolean();
        try (var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected void onRecycle(ObjectHolder<StringBuilder> holder) {
                recyclingNow.set(true); entered.countDown();
                try { await(release); super.onRecycle(holder); } finally { recyclingNow.set(false); }
            }
            @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) {
                if (recyclingNow.get()) overlap.set(true); super.onDestroy(holder);
            }
        }; var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            var task = executor.submit(lease::recycle);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); pool.close();
                assertEquals(0, pool.destroyed.get()); assertEquals(1, pool.stats().quarantined());
                assertFalse(lease.recycle());
            } finally { release.countDown(); }
            assertTrue(task.get(5, TimeUnit.SECONDS));
            assertFalse(overlap.get()); assertEquals(1, pool.destroyed.get()); assertEmpty(pool.stats()); sub.close();
        }
    }

    @Test void revokedSubsAndQuarantinedHoldersStillConsumeCapacity() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cfg = ContainerConfig.builder().initial(1).min(1).max(1).initialObjectsPerSub(1).maxSubContainers(1).build();
        try (var logs = new ContainerLifecycleTest.Logs(); var pool = new Pool(cfg);
             var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            var task = executor.submit(() -> lease.use(v -> { entered.countDown(); await(release); }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); sub.close();
                assertEquals(1, pool.stats().quarantined());
                assertThrows(PoolExhaustedException.class, pool::borrowContainer);
                pool.patrol(); assertEquals(1, pool.created.get()); assertEquals(1, pool.stats().accounted());
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS); assertEquals(2, pool.created.get());
            assertEquals(0, pool.stats().revokedSubs()); pool.borrowContainer().close();
        }
    }

    @Test void concurrentFactoryReservationsCannotExceedGlobalMax() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(0, 0, 2, 1)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() {
                entered.countDown(); await(release); return super.createNewHolder();
            }
        }; var executor = Executors.newFixedThreadPool(2)) {
            pool.start(); var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> {
                try (var sub = pool.borrowContainer()) { sub.borrowObjectHolder().close(); }
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(2, pool.stats().creating());
                assertThrows(PoolExhaustedException.class, pool::borrowContainer);
                assertEquals(2, pool.stats().accounted());
            } finally { release.countDown(); }
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(2, pool.created.get()); assertEquals(2, pool.stats().mainIdle());
            assertEquals(0, pool.stats().creating()); assertEquals(0, pool.stats().activeSubs());
        }
    }

    @Test void lifecycleHooksRunWithoutMetadataLockAndReentryIsRejected() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(0, 0, 1, 1)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() {
                assertThrows(IllegalStateException.class, this::stats);
                entered.countDown(); await(release); return super.createNewHolder();
            }
        }; var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var task = executor.submit(() -> { try (var sub = pool.borrowContainer()) {} });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(1, pool.stats().creating());
                assertEquals(0, pool.patrol().detected());
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void mainCloseDuringOnBorrowDiscardsPreparationAndNeverPublishesSub() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected SubContainer<StringBuilder> createSubContainer() {
                return new SubContainer<>(this) {
                    @Override protected void onBorrow() { entered.countDown(); await(release); }
                };
            }
        }; var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var task = executor.submit(pool::borrowContainer);
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); pool.close(); }
            finally { release.countDown(); }
            assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(1, pool.destroyed.get()); assertEmpty(pool.stats());
        }
    }

    @Test void nestedAndConcurrentPatrolSkipAndListenerCanForceFromAnotherThread() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var clock = new AtomicLong(); var eventRef = new AtomicReference<LeakEvent>();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(10)).build();
        try (var logs = new ContainerLifecycleTest.Logs(); var pool = new Pool(cfg, event -> {
            eventRef.set(event); entered.countDown(); await(release);
        }, clock); var executor = Executors.newSingleThreadExecutor()) {
            pool.start(); var sub = pool.borrowContainer(); sub.borrowObjectHolder(); clock.set(10);
            var task = executor.submit(() -> { return pool.patrol(); });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(pool.patrol().skipped());
                assertTrue(eventRef.get().forceClose()); assertFalse(eventRef.get().forceClose());
            } finally { release.countDown(); }
            assertEquals(1, task.get(5, TimeUnit.SECONDS).forcedClosed()); sub.close();
        }
        var ref = new AtomicReference<Pool>();
        try (var pool = new Pool(cfg, event -> assertTrue(ref.get().patrol().skipped()), clock)) {
            ref.set(pool); pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            clock.addAndGet(10); pool.patrol(); lease.close(); sub.close();
        }
    }

    @Test void automaticPatrolRunsOnDaemonAndSurvivesListenerException() throws Exception {
        var clock = new AtomicLong(); var calls = new AtomicInteger(); var completed = new CountDownLatch(1);
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(10)).patrolInterval(Duration.ofMillis(5)).automaticPatrol(true).build();
        try (var logs = new ContainerLifecycleTest.Logs(); var pool = new Pool(cfg, event -> {
            assertTrue(Thread.currentThread().isDaemon());
            if (calls.incrementAndGet() == 1) throw new IllegalArgumentException("first detection");
            event.forceClose(); completed.countDown();
        }, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            clock.set(10); assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(calls.get() >= 2); assertFalse(lease.recycle()); assertEquals(1, pool.destroyed.get()); sub.close();
        }
    }

    @Test void racingWorkerReturnWithSubClosePreservesAccountingAndCleansOnce() throws Exception {
        try (var logs = new ContainerLifecycleTest.Logs(); var pool = new Pool(config(1, 0, 1, 1));
             var executor = Executors.newSingleThreadExecutor()) {
            pool.start();
            for (int i = 0; i < 100; i++) {
                var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder(); var barrier = new CyclicBarrier(2);
                int before = pool.destroyed.get() + pool.recycled.get();
                var returned = executor.submit(() -> { rendezvous(barrier); return lease.recycle(); });
                rendezvous(barrier); sub.close(); boolean accepted = returned.get(5, TimeUnit.SECONDS);
                SubStats stats = sub.stats();
                assertEquals(1, stats.borrowCount()); assertEquals(accepted ? 1 : 0, stats.recycleCount());
                assertEquals(accepted ? 0 : 1, stats.forcedCloseCount());
                assertEquals(0, stats.outstandingCount()); assertEquals(0, stats.pendingCleanupCount());
                assertEquals(before + 1, pool.destroyed.get() + pool.recycled.get());
                assertEquals(0, pool.stats().revokedSubs());
            }
        }
    }

    @Test void concurrentLoadNeverSharesAValueAndFinishesWithOnlyIdleSlots() throws Exception {
        var active = Collections.newSetFromMap(new ConcurrentHashMap<StringBuilder, Boolean>());
        try (var pool = new Pool(config(16, 8, 32, 2)); var executor = Executors.newFixedThreadPool(8)) {
            pool.start(); var tasks = new ArrayList<Future<?>>();
            for (int t = 0; t < 8; t++) tasks.add(executor.submit(() -> {
                for (int i = 0; i < 200; i++) {
                    try (var sub = pool.borrowContainer()) {
                        var a = sub.borrowObjectHolder(); var b = sub.borrowObjectHolder();
                        a.use(v -> { assertTrue(active.add(v)); v.append("a"); assertTrue(active.remove(v)); });
                        b.use(v -> { assertTrue(active.add(v)); assertEquals(0, v.length()); assertTrue(active.remove(v)); });
                        a.close(); b.close();
                    }
                    ContainerStats stats = pool.stats();
                    assertTrue(stats.accounted() <= 32);
                    assertEquals(stats.total(), stats.mainIdle() + stats.subStack() + stats.subReturns()
                            + stats.borrowed() + stats.recycling() + stats.quarantined() + stats.destroying());
                }
            }));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
            assertTrue(active.isEmpty()); var stats = pool.stats();
            assertEquals(stats.total(), stats.mainIdle()); assertEquals(0, stats.borrowed());
            assertEquals(0, stats.recycling()); assertEquals(0, stats.quarantined());
            assertEquals(0, stats.creating()); assertEquals(0, stats.activeSubs()); assertEquals(0, stats.revokedSubs());
        }
    }

    private static void assertEmpty(ContainerStats stats) {
        assertEquals(0, stats.total()); assertEquals(0, stats.creating());
        assertEquals(0, stats.activeSubs()); assertEquals(0, stats.revokedSubs());
    }
    private static void rendezvous(CyclicBarrier barrier) {
        try { barrier.await(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
