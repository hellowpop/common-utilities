package com.inzent.apim.utilities.pool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.*;
import java.util.stream.Stream;
import static com.inzent.apim.utilities.pool.PoolLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PoolDiagnosticsTest {
    static Stream<Throwable> loggingFailures() {
        return Stream.of(new NullPointerException("logging NPE"),
                new IllegalStateException("logging runtime"), new AssertionError("logging error"));
    }
    static void raise(Throwable failure) {
        if (failure instanceof Error error) throw error;
        throw (RuntimeException) failure;
    }
    static final class Logging implements AutoCloseable {
        final Logger logger = Logger.getLogger(MainPool.class.getName());
        final Level previousLevel = logger.getLevel();
        final boolean previousParents = logger.getUseParentHandlers();
        final Handler handler;
        Logging(Consumer<LogRecord> action) {
            handler = new Handler() {
                @Override public void publish(LogRecord record) { action.accept(record); }
                @Override public void flush() {}
                @Override public void close() {}
            };
            logger.setUseParentHandlers(false); logger.setLevel(Level.ALL); logger.addHandler(handler);
        }
        @Override public void close() {
            logger.removeHandler(handler); logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousParents);
        }
    }

    @ParameterizedTest @MethodSource("loggingFailures")
    void failedLoggingCannotStrandIdleOrLeaveLaterSubOpen(Throwable failure) throws Exception {
        var pool = new Pool(config(0, 4, 0, 4)); pool.start();
        var first = pool.borrowSubPool(); var second = pool.borrowSubPool();
        var h1 = first.borrowObjectHolder(); var idle1 = first.borrowObjectHolder(); idle1.close();
        var h2 = second.borrowObjectHolder(); var idle2 = second.borrowObjectHolder(); idle2.close();
        try (var logging = new Logging(record -> raise(failure))) {
            assertSame(failure, assertThrows(failure.getClass(), pool::close));
            assertMain(pool, 2, 0, 0);
            assertEquals(2, pool.destroyed.get());
            assertEquals(0, pool.stats().destroyingObjectCount());
            assertTrue(first.stats().closed()); assertTrue(second.stats().closed());
        } finally {
            h1.close(); h2.close(); first.close(); second.close(); pool.close();
        }
        assertTrue(pool.isTerminated()); assertMain(pool, 0, 0, 0);
        assertEquals(4, pool.destroyed.get());
    }

    @ParameterizedTest @MethodSource("loggingFailures")
    void subCloseRemainsUsableForLateReturnDespiteLoggingFailure(Throwable failure) throws Exception {
        try (var pool = new Pool(config(0, 2, 0, 2))) {
            pool.start(); var sub = pool.borrowSubPool(); var holder = sub.borrowObjectHolder();
            var idle = sub.borrowObjectHolder(); idle.close();
            try (var logging = new Logging(record -> raise(failure))) {
                assertSame(failure, assertThrows(failure.getClass(), sub::close));
                assertSub(sub, 0, 1, 0); assertMain(pool, 2, 1, 0);
                holder.close(); sub.close();
                assertSub(sub, 0, 0, 0); assertMain(pool, 2, 2, 0);
            } finally { holder.close(); sub.close(); }
        }
    }

    @Test void cleanupPrecedesLoggingAndWarningsExecuteOutsideMainLock() throws Exception {
        var pool = new Pool(config(0, 2, 0, 2)); pool.start();
        var sub = pool.borrowSubPool(); var h = sub.borrowObjectHolder();
        var idle = sub.borrowObjectHolder(); idle.close();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (var logging = new Logging(record -> {
            try {
                var stats = reader.submit(pool::stats).get(2, TimeUnit.SECONDS);
                assertEquals(1, pool.destroyed.get(), "physical cleanup must precede diagnostics");
                assertEquals(0, stats.destroyingObjectCount());
            } catch (Exception failure) { throw new AssertionError("logger held Main lock", failure); }
        })) {
            pool.close();
        } finally {
            h.close(); pool.close(); reader.shutdownNow();
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(pool.isTerminated());
    }

    @Test void cleanupErrorAndMultipleLoggingErrorsArePreservedAfterAllAttempts() throws Exception {
        var destroy = new IOException("destroy"); var loggerError = new AssertionError("logger");
        var loggerNpe = new NullPointerException("logger NPE");
        var pool = new Pool(config(0, 4, 0, 4)) {
            @Override protected void destroyTargetObject(Resource target) throws Exception {
                super.destroyTargetObject(target); throw destroy;
            }
        };
        pool.start(); var a = pool.borrowSubPool(); var b = pool.borrowSubPool();
        var h1 = a.borrowObjectHolder(); var h2 = b.borrowObjectHolder();
        var i1 = a.borrowObjectHolder(); var i2 = b.borrowObjectHolder(); i1.close(); i2.close();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var logging = new Logging(record -> {
            if (calls.incrementAndGet() == 1) throw loggerError;
            throw loggerNpe;
        })) {
            assertSame(loggerError, assertThrows(AssertionError.class, pool::close));
            assertEquals(2, calls.get()); assertEquals(2, pool.destroyed.get());
            assertArrayEquals(new Throwable[]{destroy, loggerNpe}, loggerError.getSuppressed());
            assertEquals(0, pool.stats().activeSubPoolCount());
        } finally {
            assertThrows(IOException.class, h1::close); assertThrows(IOException.class, h2::close);
            pool.close();
        }
        assertTrue(pool.isTerminated()); assertEquals(4, pool.destroyed.get());
    }
}
