package com.inzent.apim.utilities.pool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.inzent.apim.utilities.pool.PoolLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PoolConcurrencyTest {
    static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timed out");
    }
    static final class Workers implements AutoCloseable {
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        @Override public void close() throws InterruptedException {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void startupCloseCleansInFlightGeneration() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = new Pool(config(1, 1, 0, 1)) {
            @Override protected Resource generateTargetObject() throws Exception {
                entered.countDown(); await(release); return super.generateTargetObject();
            }
        };
        try (var workers = new Workers()) {
            var start = workers.executor.submit(() -> assertThrows(IOException.class, pool::start));
            try {
                await(entered); pool.close(); assertFalse(pool.isTerminated());
                assertEquals(1, pool.stats().reservedCreationCount());
            } finally { release.countDown(); }
            start.get(5, TimeUnit.SECONDS);
            assertTrue(pool.isTerminated()); assertEquals(1, pool.destroyed.get());
            assertMain(pool, 0, 0, 0);
        } finally { release.countDown(); pool.close(); }
    }

    @Test void holderCreationRacingSubCloseDiscardsNewObject() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(0, 1, 0, 1)) {
            @Override protected Resource generateTargetObject() throws Exception {
                entered.countDown(); await(release); return super.generateTargetObject();
            }
        }; var workers = new Workers()) {
            pool.start(); var sub = pool.borrowSubPool();
            var borrow = workers.executor.submit(() -> assertThrows(IllegalStateException.class, sub::borrowObjectHolder));
            try { await(entered); sub.close(); } finally { release.countDown(); }
            borrow.get(5, TimeUnit.SECONDS);
            assertMain(pool, 0, 0, 0); assertEquals(1, pool.destroyed.get());
        } finally { release.countDown(); }
    }

    @Test void preparingSubIdleCannotBeStolenBeforePublication() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(new PoolConfig(0, 2, 2, 2, 2)) {
            final AtomicInteger calls = new AtomicInteger();
            @Override protected Resource generateTargetObject() throws Exception {
                if (calls.incrementAndGet() == 2) { entered.countDown(); await(release); }
                return super.generateTargetObject();
            }
        }; var workers = new Workers()) {
            pool.start(); var borrow = workers.executor.submit(pool::borrowSubPool);
            try {
                await(entered);
                assertEquals(1, pool.stats().preparingSubPoolCount());
                assertEquals(0, pool.stats().activeSubPoolCount());
                assertThrows(PoolExhaustedException.class, pool::borrowSubPool);
            } finally { release.countDown(); }
            var sub = borrow.get(5, TimeUnit.SECONDS);
            assertSub(sub, 2, 2, 2); sub.close();
        } finally { release.countDown(); }
    }

    @Test void preparingSubConsumesSubCapacityAndMainCloseCancelsIt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = new Pool(new PoolConfig(0, 1, 1, 1, 1)) {
            @Override protected Resource generateTargetObject() throws Exception {
                entered.countDown(); await(release); return super.generateTargetObject();
            }
        };
        try (var workers = new Workers()) {
            pool.start();
            var borrow = workers.executor.submit(() -> assertThrows(IllegalStateException.class, pool::borrowSubPool));
            try {
                await(entered); assertThrows(PoolExhaustedException.class, pool::borrowSubPool);
                pool.close(); assertFalse(pool.isTerminated());
            } finally { release.countDown(); }
            borrow.get(5, TimeUnit.SECONDS);
            assertTrue(pool.isTerminated()); assertEquals(1, pool.destroyed.get());
            assertEquals(0, pool.stats().subBorrowCount());
        } finally { release.countDown(); pool.close(); }
    }

    @Test void duplicateCloseFromManyThreadsRecyclesOnce() throws Exception {
        try (var pool = new Pool(config(0, 1, 0, 1)); var workers = new Workers()) {
            pool.start(); var sub = pool.borrowSubPool(); var holder = sub.borrowObjectHolder();
            var gate = new CountDownLatch(1); var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) futures.add(workers.executor.submit(() -> {
                await(gate); holder.close(); return null;
            }));
            gate.countDown(); for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, pool.stats().holderRecycleCount()); assertSub(sub, 0, 1, 1);
            var next = sub.borrowObjectHolder(); holder.close(); assertNotNull(next.get());
            next.close(); sub.close();
        }
    }

    @Test void reservedCreationCountsAgainstCapacity() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new Pool(config(0, 1, 0, 1)) {
            @Override protected Resource generateTargetObject() throws Exception {
                entered.countDown(); await(release); return super.generateTargetObject();
            }
        }; var workers = new Workers()) {
            pool.start(); var a = pool.borrowSubPool(); var b = pool.borrowSubPool();
            var borrow = workers.executor.submit(a::borrowObjectHolder);
            try {
                await(entered); assertThrows(PoolExhaustedException.class, b::borrowObjectHolder);
                assertEquals(1, pool.stats().reservedCreationCount());
            } finally { release.countDown(); }
            borrow.get(5, TimeUnit.SECONDS).close(); a.close(); b.close();
        } finally { release.countDown(); }
    }

    @Test void concurrentReturnAndMainCloseDestroyEveryTargetOnce() throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            var pool = new Pool(config(0, 4, 0, 4));
            try (var workers = new Workers()) {
                pool.start(); var sub = pool.borrowSubPool();
                var holders = new ArrayList<ObjectHolder<Resource>>();
                var targets = new ArrayList<Resource>();
                for (int i = 0; i < 4; i++) {
                    var h = sub.borrowObjectHolder(); holders.add(h); targets.add(h.get());
                }
                var gate = new CountDownLatch(1); var futures = new ArrayList<Future<?>>();
                for (var h : holders) futures.add(workers.executor.submit(() -> {
                    await(gate); h.close(); return null;
                }));
                futures.add(workers.executor.submit(() -> { await(gate); pool.close(); return null; }));
                gate.countDown(); for (var future : futures) future.get(5, TimeUnit.SECONDS);
                assertTrue(pool.isTerminated()); assertMain(pool, 0, 0, 0);
                assertSub(sub, 0, 0, 0); assertEquals(4, pool.destroyed.get());
                for (var target : targets) assertEquals(1, target.closed);
            } finally { pool.close(); }
        }
    }
}
