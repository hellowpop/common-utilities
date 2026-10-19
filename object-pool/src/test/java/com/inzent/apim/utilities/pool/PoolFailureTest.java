package com.inzent.apim.utilities.pool;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import static com.inzent.apim.utilities.pool.PoolLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PoolFailureTest {
    @Test void failedStartDestroysEarlierObjectsAndCannotRestart() throws Exception {
        var pool = new Pool(config(3, 3, 0, 1)) {
            @Override protected Resource generateTargetObject() throws Exception {
                if (generated.get() == 1) throw new Exception("creation");
                return super.generateTargetObject();
            }
        };
        var failure = assertThrows(IOException.class, pool::start);
        assertEquals("creation", failure.getCause().getMessage());
        assertTrue(pool.isTerminated()); assertEquals(1, pool.destroyed.get());
        assertMain(pool, 0, 0, 0);
        assertEquals(0, pool.stats().reservedCreationCount());
        assertThrows(IllegalStateException.class, pool::start);
    }

    @Test void failedSubPreparationReturnsEarlierObjectsAndReleasesSubSlot() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 3, 1, 2, 2)) {
            boolean fail = true;
            @Override protected Resource generateTargetObject() throws Exception {
                if (fail && generated.get() == 1) { fail = false; throw new IOException("creation"); }
                return super.generateTargetObject();
            }
        }) {
            pool.start(); assertThrows(IOException.class, pool::borrowSubPool);
            assertMain(pool, 1, 1, 0);
            assertEquals(0, pool.stats().preparingSubPoolCount());
            assertEquals(0, pool.stats().reservedCreationCount());
            var sub = pool.borrowSubPool(); assertSub(sub, 2, 2, 2); sub.close();
        }
    }

    @Test void failedSubCapacityRollsBackWithoutCountingSuccessfulBorrow() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 2, 2, 2, 2))) {
            pool.start(); var sub = pool.borrowSubPool();
            var holder = sub.borrowObjectHolder();
            assertThrows(PoolExhaustedException.class, pool::borrowSubPool);
            assertMain(pool, 2, 1, 1); assertSub(sub, 2, 1, 0);
            assertEquals(1, pool.stats().subBorrowCount());
            assertEquals(0, pool.stats().preparingSubPoolCount());
            holder.close(); sub.close();
        }
    }

    @Test void nullCreationDoesNotConsumeCapacity() throws Exception {
        try (var pool = new Pool(config(0, 1, 0, 1)) {
            boolean fail = true;
            @Override protected Resource generateTargetObject() throws Exception {
                if (fail) { fail = false; return null; }
                return super.generateTargetObject();
            }
        }) {
            pool.start(); var sub = pool.borrowSubPool();
            assertThrows(IOException.class, sub::borrowObjectHolder);
            assertMain(pool, 0, 0, 1); assertEquals(0, pool.stats().reservedCreationCount());
            var holder = sub.borrowObjectHolder(); holder.close(); sub.close();
            assertEquals(1, pool.stats().creationFailureCount());
        }
    }

    @Test void duplicateTargetIsRejectedWithoutDestroyingOriginal() throws Exception {
        Resource shared = new Resource();
        try (var pool = new Pool(config(0, 2, 0, 1)) {
            @Override protected Resource generateTargetObject() { return shared; }
        }) {
            pool.start(); var sub = pool.borrowSubPool(); var holder = sub.borrowObjectHolder();
            assertThrows(IOException.class, sub::borrowObjectHolder);
            assertEquals(0, shared.closed); assertSame(shared, holder.get());
            assertMain(pool, 1, 0, 1); assertEquals(0, pool.stats().reservedCreationCount());
            holder.close(); sub.close();
        }
        assertEquals(1, shared.closed);
    }

    @Test void destroyFailureDoesNotStopBatchAndErrorHasPriority() throws Exception {
        var pool = new Pool(config(3, 3, 0, 1)) {
            @Override protected void destroyTargetObject(Resource value) throws Exception {
                super.destroyTargetObject(value);
                if (destroyed.get() == 2) throw new AssertionError("error");
                throw new IOException("io");
            }
        };
        pool.start(); var failure = assertThrows(AssertionError.class, pool::close);
        assertEquals("error", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(3, pool.destroyed.get()); assertTrue(pool.isTerminated());
        assertEquals(3, pool.stats().destroyFailureCount());
        pool.close(); assertEquals(3, pool.destroyed.get());
    }

    @Test void delayedDestroyFailureIsReportedByHolderAndDetachesHandles() throws Exception {
        var pool = new Pool(config(0, 1, 0, 1)) {
            @Override protected void destroyTargetObject(Resource value) throws Exception {
                super.destroyTargetObject(value); throw new IOException("destroy");
            }
        };
        pool.start(); var sub = pool.borrowSubPool(); var holder = sub.borrowObjectHolder();
        pool.close(); assertThrows(IOException.class, holder::close);
        assertTrue(pool.isTerminated()); holder.close(); sub.close();
        assertSub(sub, 0, 0, 0); assertEquals(1, pool.destroyed.get());
        assertNoMainOrTargetReferences(holder); assertNoMainOrTargetReferences(sub);
    }

    @Test void lifecycleHookReentryFailsAndStatsRemainReadable() throws Exception {
        try (var pool = new Pool(config(0, 1, 0, 1)) {
            @Override protected Resource generateTargetObject() throws Exception {
                assertEquals(1, stats().reservedCreationCount());
                assertThrows(IllegalStateException.class, this::close);
                assertThrows(IllegalStateException.class, this::borrowSubPool);
                return super.generateTargetObject();
            }
            @Override protected void destroyTargetObject(Resource value) throws Exception {
                assertEquals(1, stats().destroyingObjectCount());
                assertThrows(IllegalStateException.class, this::start);
                super.destroyTargetObject(value);
            }
        }) {
            pool.start(); var sub = pool.borrowSubPool(); var h = sub.borrowObjectHolder();
            h.close(); sub.close();
        }
    }

    @Test void startFailureKeepsCleanupFailureAsSuppressed() {
        var pool = new Pool(config(2, 2, 0, 1)) {
            @Override protected Resource generateTargetObject() throws Exception {
                if (generated.get() == 1) throw new IOException("generate");
                return super.generateTargetObject();
            }
            @Override protected void destroyTargetObject(Resource value) throws Exception {
                super.destroyTargetObject(value); throw new IOException("destroy");
            }
        };
        var ex = assertThrows(IOException.class, pool::start);
        assertEquals("generate", ex.getMessage());
        assertEquals("destroy", ex.getSuppressed()[0].getMessage());
        assertTrue(pool.isTerminated());
    }

    @Test void returnedHandlesDoNotRetainPoolOrResource() throws Exception {
        try (var pool = new Pool(config(0, 1, 0, 1))) {
            pool.start(); var sub = pool.borrowSubPool(); var h = sub.borrowObjectHolder();
            h.close(); sub.close();
            assertNoMainOrTargetReferences(h); assertNoMainOrTargetReferences(sub);
        }
    }

    private static void assertNoMainOrTargetReferences(Object handle) throws Exception {
        var objects = new ArrayList<Object>(); objects.add(handle);
        if (handle instanceof SubPool<?> sub) objects.add(sub.state);
        for (Object object : objects) {
            for (Field field : object.getClass().getDeclaredFields()) {
                field.setAccessible(true); Object value = field.get(object);
                assertFalse(value instanceof MainPool<?> || value instanceof Resource, field.getName());
            }
        }
    }
}
