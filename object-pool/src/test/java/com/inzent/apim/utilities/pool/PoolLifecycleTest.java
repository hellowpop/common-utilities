package com.inzent.apim.utilities.pool;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PoolLifecycleTest {
    static class Resource {
        int closed;
    }
    static class Pool extends MainPool<Resource> {
        final AtomicInteger generated = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        Pool(PoolConfig config) { super(config); }
        @Override protected Resource generateTargetObject() throws Exception {
            generated.incrementAndGet(); return new Resource();
        }
        @Override protected void destroyTargetObject(Resource value) throws Exception {
            value.closed++; destroyed.incrementAndGet();
        }
    }
    static PoolConfig config(int initial, int max, int subInitial, int cache) {
        return new PoolConfig(initial, max, 4, subInitial, cache);
    }

    @Test void tracksSixRequiredCountsThroughNormalLifecycle() throws Exception {
        var pool = new Pool(config(3, 5, 2, 3));
        pool.start();
        assertMain(pool, 3, 3, 0);
        var sub = pool.borrowSubPool();
        assertMain(pool, 3, 1, 1);
        assertSub(sub, 2, 2, 2);
        var holder = sub.borrowObjectHolder();
        Resource resource = holder.get();
        assertSub(sub, 2, 2, 1);
        assertMain(pool, 3, 1, 1);
        holder.close();
        assertSub(sub, 2, 2, 2);
        assertThrows(IllegalStateException.class, holder::get);
        var next = sub.borrowObjectHolder();
        assertNotSame(holder, next);
        assertSame(resource, next.get());
        holder.close(); // old generation cannot recycle next
        assertSame(resource, next.get());
        next.close();
        sub.close();
        assertSub(sub, 2, 0, 0);
        assertMain(pool, 3, 3, 0);
        assertEquals(2, sub.stats().holderRecycleCount());
        pool.close();
        assertTrue(pool.isTerminated());
        assertMain(pool, 0, 0, 0);
        assertEquals(3, pool.destroyed.get());
        pool.close(); sub.close(); next.close();
        assertEquals(3, pool.destroyed.get());
    }

    @Test void closedSubAcceptsLateReturnToMain() throws Exception {
        try (var pool = new Pool(config(0, 2, 0, 2))) {
            pool.start();
            var sub = pool.borrowSubPool();
            var holder = sub.borrowObjectHolder();
            sub.close();
            assertMain(pool, 1, 0, 0);
            assertSub(sub, 0, 1, 0);
            assertNotNull(holder.get());
            assertThrows(IllegalStateException.class, sub::borrowObjectHolder);
            sub.recycleObjectHolder(holder);
            assertSub(sub, 0, 0, 0);
            assertMain(pool, 1, 1, 0);
        }
    }

    @Test void mainCloseDefersBorrowedTargetDestruction() throws Exception {
        var pool = new Pool(config(2, 2, 2, 2));
        pool.start(); var sub = pool.borrowSubPool();
        var holder = sub.borrowObjectHolder(); var resource = holder.get();
        pool.close();
        assertEquals(PoolState.CLOSING, pool.stats().state());
        assertFalse(pool.isTerminated());
        assertMain(pool, 1, 0, 0);
        assertSub(sub, 2, 1, 0);
        assertEquals(0, resource.closed);
        assertThrows(IllegalStateException.class, holder::get);
        holder.close();
        assertEquals(1, resource.closed);
        assertSub(sub, 2, 0, 0);
        assertTrue(pool.isTerminated());
        assertEquals(2, pool.destroyed.get());
    }

    @Test void localCacheOverflowReturnsToMainWithoutStealingOtherSubIdle() throws Exception {
        try (var pool = new Pool(config(0, 2, 0, 1))) {
            pool.start(); var a = pool.borrowSubPool(); var b = pool.borrowSubPool();
            var h1 = a.borrowObjectHolder(); var h2 = a.borrowObjectHolder();
            h1.close(); h2.close();
            assertSub(a, 0, 1, 1); assertMain(pool, 2, 1, 2);
            var b1 = b.borrowObjectHolder(); var b2 = b.borrowObjectHolder();
            assertSub(a, 0, 1, 1); assertSub(b, 0, 2, 0);
            var a1 = a.borrowObjectHolder();
            b1.close(); b2.close(); a1.close(); a.close(); b.close();
            assertMain(pool, 2, 2, 0); assertEquals(1, pool.destroyed.get());
        }
    }

    @Test void rejectsForeignHandlesWithoutChangingOwnership() throws Exception {
        try (var a = new Pool(config(0, 2, 0, 1)); var b = new Pool(config(0, 2, 0, 1))) {
            a.start(); b.start();
            var s1 = a.borrowSubPool(); var s2 = a.borrowSubPool(); var foreign = b.borrowSubPool();
            var h = s1.borrowObjectHolder();
            assertThrows(IllegalArgumentException.class, () -> s2.recycleObjectHolder(h));
            assertThrows(IllegalArgumentException.class, () -> foreign.recycleObjectHolder(h));
            assertThrows(IllegalArgumentException.class, () -> b.recycleSubPool(s1));
            assertThrows(NullPointerException.class, () -> s1.recycleObjectHolder(null));
            assertNotNull(h.get()); h.close(); s1.close(); s2.close(); foreign.close();
        }
    }

    @Test void startAndShutdownHaveTerminalSemanticsAndSubCapacity() throws Exception {
        var pool = new Pool(new PoolConfig(0, 1, 1, 0, 0));
        assertThrows(IllegalStateException.class, pool::borrowSubPool);
        pool.start(); pool.start();
        var sub = pool.borrowSubPool();
        assertThrows(PoolExhaustedException.class, pool::borrowSubPool);
        sub.close(); var next = pool.borrowSubPool(); assertNotSame(sub, next);
        var h = next.borrowObjectHolder(); h.close();
        assertSub(next, 0, 0, 0); assertMain(pool, 1, 1, 1);
        pool.close();
        assertThrows(IllegalStateException.class, pool::start);
        assertThrows(IllegalStateException.class, pool::borrowSubPool);
        var unused = new Pool(config(0, 1, 0, 0)); unused.close();
        assertTrue(unused.isTerminated()); assertEquals(0, unused.generated.get());
    }

    @Test void invalidConfigurationFailsBeforeAnyGeneration() {
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(-1, 2, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(3, 2, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0, 2, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0, 2, 1, 2, 1));
    }

    static void assertMain(MainPool<?> pool, int created, int idle, int active) {
        var s = pool.stats();
        assertEquals(created, s.createdObjectCount());
        assertEquals(idle, s.idleObjectCount());
        assertEquals(active, s.activeSubPoolCount());
        assertEquals(s.holderBorrowCount() - s.holderRecycleCount(), s.borrowedObjectCount());
        assertEquals(s.createdObjectCount(), s.totalIdleObjectCount() + s.borrowedObjectCount()
                + s.returningObjectCount() + s.destroyingObjectCount());
    }
    static void assertSub(SubPool<?> sub, int initial, int managed, int idle) {
        var s = sub.stats();
        assertEquals(initial, s.initialObjectCount());
        assertEquals(managed, s.managedObjectCount());
        assertEquals(idle, s.idleObjectCount());
        assertEquals(managed, idle + s.borrowedCount() + s.returningCount());
    }
}
