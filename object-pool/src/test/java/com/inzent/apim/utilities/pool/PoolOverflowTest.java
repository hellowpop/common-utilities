package com.inzent.apim.utilities.pool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.*;
import static com.inzent.apim.utilities.pool.PoolLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PoolOverflowTest {
    @Test void borrowExceedsMaxAndReturnDestroysOnlyMainIdleOverflow() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 2, 1, 0, 0))) {
            pool.start(); var sub = pool.borrowSubPool();
            var holders = new ArrayList<ObjectHolder<Resource>>();
            var targets = new ArrayList<Resource>();
            for (int i = 0; i < 5; i++) {
                var h = sub.borrowObjectHolder(); holders.add(h); targets.add(h.get());
            }
            assertMain(pool, 5, 0, 1); assertEquals(5, pool.generated.get());
            holders.get(0).close(); holders.get(1).close();
            assertMain(pool, 5, 2, 1); assertEquals(0, pool.destroyed.get());
            for (int i = 2; i < 5; i++) holders.get(i).close();
            assertMain(pool, 2, 2, 1); assertEquals(3, pool.destroyed.get());
            assertSub(sub, 0, 0, 0);
            for (int i = 0; i < 5; i++) assertEquals(i < 2 ? 0 : 1, targets.get(i).closed);
            sub.close();
        }
    }

    @Test void subInitialInstancesAndLocalCacheMayExceedMainIdleLimit() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 4, 4))) {
            pool.start(); var sub = pool.borrowSubPool();
            assertSub(sub, 4, 4, 4); assertMain(pool, 4, 0, 1);
            try (var h = sub.borrowObjectHolder()) { assertSub(sub, 4, 4, 3); }
            assertSub(sub, 4, 4, 4); assertEquals(0, pool.destroyed.get());
            sub.close(); assertMain(pool, 1, 1, 0); assertSub(sub, 4, 0, 0);
            assertEquals(3, pool.destroyed.get());
        }
    }

    @Test void lateReturnsToMainAreEvictedAtIdleLimit() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 0, 0))) {
            pool.start(); var sub = pool.borrowSubPool();
            var a = sub.borrowObjectHolder(); var b = sub.borrowObjectHolder(); var c = sub.borrowObjectHolder();
            sub.close(); a.close(); b.close(); c.close();
            assertMain(pool, 1, 1, 0); assertSub(sub, 0, 0, 0);
            assertEquals(2, pool.destroyed.get());
        }
    }

    @Test void localCacheOverflowIsCheckedAgainAgainstMainIdleLimit() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 0, 1))) {
            pool.start(); var sub = pool.borrowSubPool();
            var a = sub.borrowObjectHolder(); var b = sub.borrowObjectHolder();
            var c = sub.borrowObjectHolder(); var d = sub.borrowObjectHolder();
            a.close(); b.close(); c.close(); d.close();
            assertSub(sub, 0, 1, 1); assertMain(pool, 2, 1, 1);
            assertEquals(2, pool.destroyed.get());
            sub.close(); assertMain(pool, 1, 1, 0); assertEquals(3, pool.destroyed.get());
        }
    }

    @Test void failedOverflowDestroyStillReleasesHolderAndRegistry() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 0, 0)) {
            @Override protected void destroyTargetObject(Resource target) throws Exception {
                super.destroyTargetObject(target);
                if (destroyed.get() == 1) throw new IOException("overflow destroy");
            }
        }) {
            pool.start(); var sub = pool.borrowSubPool();
            var a = sub.borrowObjectHolder(); var b = sub.borrowObjectHolder();
            a.close(); assertThrows(IOException.class, b::close); b.close();
            assertMain(pool, 1, 1, 1); assertSub(sub, 0, 0, 0);
            assertEquals(0, pool.stats().destroyingObjectCount());
            assertEquals(1, pool.stats().destroyFailureCount()); sub.close();
        }
    }

    @Test void subCloseAttemptsEveryOverflowDestroyDespiteFailures() throws Exception {
        var pool = new Pool(new PoolConfig(0, 1, 1, 3, 3)) {
            @Override protected void destroyTargetObject(Resource target) throws Exception {
                super.destroyTargetObject(target); throw new IOException("destroy " + destroyed.get());
            }
        };
        pool.start(); var sub = pool.borrowSubPool();
        var failure = assertThrows(IOException.class, sub::close);
        assertEquals(1, failure.getSuppressed().length); assertEquals(2, pool.destroyed.get());
        assertMain(pool, 1, 1, 0); assertSub(sub, 3, 0, 0);
        assertThrows(IOException.class, pool::close); assertTrue(pool.isTerminated());
    }

    @Test void concurrentReturnsNeverExceedMainIdleLimit() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 2, 1, 0, 0));
             var workers = new PoolConcurrencyTest.Workers()) {
            pool.start(); var sub = pool.borrowSubPool();
            var holders = new ArrayList<ObjectHolder<Resource>>();
            for (int i = 0; i < 8; i++) holders.add(sub.borrowObjectHolder());
            var gate = new CountDownLatch(1); var futures = new ArrayList<Future<?>>();
            for (var h : holders) futures.add(workers.executor.submit(() -> {
                PoolConcurrencyTest.await(gate); h.close();
                assertTrue(pool.stats().idleObjectCount() <= 2); return null;
            }));
            gate.countDown(); for (var f : futures) f.get(5, TimeUnit.SECONDS);
            assertMain(pool, 2, 2, 1); assertEquals(6, pool.destroyed.get()); sub.close();
        }
    }
}
