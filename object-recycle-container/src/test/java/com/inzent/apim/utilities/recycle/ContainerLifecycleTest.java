package com.inzent.apim.utilities.recycle;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import static com.inzent.apim.utilities.recycle.MainContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerLifecycleTest {
    @Test void additionalAssignmentPushesToTheTopOfTheLifoStack() {
        try (var pool = new Pool(config(1, 0, 2, 1)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() {
                return new ObjectHolder<>(new StringBuilder("id-" + created.incrementAndGet()));
            }
        }) {
            pool.start(); var sub = pool.borrowContainer(); pool.newObject(sub);
            var newest = sub.borrowObjectHolder(); newest.use(v -> assertEquals("id-2", v.toString()));
            var oldest = sub.borrowObjectHolder(); oldest.use(v -> assertEquals("id-1", v.toString()));
            newest.close(); oldest.close(); sub.close();
        }
    }

    @Test void configRejectsInvalidCapacityAndUnsafeDurations() {
        assertThrows(IllegalArgumentException.class, () -> config(0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> config(2, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> config(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> config(1, 0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> ContainerConfig.builder().maxSubContainers(0).build());
        assertThrows(IllegalArgumentException.class, () -> ContainerConfig.builder().holderBorrowLimit(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> ContainerConfig.builder().poolIdleLimit(Duration.ofNanos(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> ContainerConfig.builder().patrolInterval(null).build());
        assertThrows(IllegalArgumentException.class, () -> ContainerConfig.builder().subIdleLimit(Duration.ofSeconds(Long.MAX_VALUE)).build());
    }

    @Test void partialSubAllocationFailureRestoresExistingIdleAndDiscardsFreshObjects() {
        try (var pool = new Pool(config(1, 0, 4, 3))) {
            pool.start(); pool.failCreationAt = 3;
            assertThrows(ObjectCreationException.class, pool::borrowContainer);
            assertEquals(1, pool.stats().mainIdle()); assertEquals(1, pool.stats().total());
            assertEquals(0, pool.stats().creating()); assertEquals(0, pool.stats().activeSubs());
            assertEquals(1, pool.destroyed.get());
            pool.failCreationAt = -1; var sub = pool.borrowContainer(); sub.close();
            assertEquals(3, pool.stats().mainIdle());
        }
    }

    @Test void recycleAndDestroyFailuresLeaveNoReusableSlotAndAllowMinRecovery() {
        try (var logs = new Logs(); var pool = new Pool(config(1, 1, 2, 1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            pool.failRecycle = pool.failDestroy = true;
            assertTrue(lease.recycle()); assertFalse(lease.recycle());
            assertEquals(1, pool.destroyed.get()); assertEquals(2, pool.created.get());
            assertEquals(1, pool.stats().total()); assertEquals(2, pool.stats().failureCount());
            assertEquals(1, sub.stats().recycleCount()); assertEquals(0, sub.stats().forcedCloseCount());
            pool.failRecycle = pool.failDestroy = false;
            sub.borrowObjectHolder().close(); sub.close();
        }
    }

    @Test void predicateFailureDestroysWithoutInvokingRecycle() {
        try (var logs = new Logs(); var pool = new Pool(config(1, 0, 1, 1)) {
            @Override protected boolean isRecycleable(ObjectHolder<StringBuilder> holder) {
                throw new IllegalArgumentException("predicate");
            }
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertTrue(lease.recycle()); assertFalse(lease.recycle());
            assertEquals(0, pool.recycled.get()); assertEquals(1, pool.destroyed.get());
            assertEquals(1, pool.stats().failureCount()); assertEquals(1, sub.stats().recycleCount()); sub.close();
        }
    }

    @Test void holderTouchExtendsIdleButNeverTotalBorrowTime() {
        var clock = new AtomicLong(); var events = new ArrayList<LeakEvent>();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(20)).holderIdleLimit(Duration.ofNanos(10)).build();
        try (var pool = new Pool(cfg, events::add, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            clock.set(9); lease.touch(); clock.set(18); lease.touch();
            assertEquals(0, pool.patrol().detected()); clock.set(20); pool.patrol();
            assertEquals(LeakEvent.Reason.BORROW_LIMIT, events.get(0).reason()); lease.close(); sub.close();
        }
    }

    @Test void failedMinRecoveryRetriesOnNextPatrolWithoutLooping() {
        try (var logs = new Logs(); var pool = new Pool(config(1, 1, 2, 1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            pool.reusable = false; pool.failCreationAt = 2; lease.close();
            assertEquals(2, pool.created.get()); assertEquals(0, pool.stats().total());
            assertEquals(0, pool.stats().creating()); assertEquals(1, pool.stats().failureCount());
            pool.patrol(); assertEquals(1, pool.stats().mainIdle()); assertEquals(3, pool.created.get()); sub.close();
        }
    }

    @Test void failedMinRecoveryAlsoRetriesOnNextBorrow() {
        try (var logs = new Logs(); var pool = new Pool(config(3, 3, 4, 1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            pool.reusable = false; pool.failCreationAt = 4; lease.close();
            assertEquals(2, pool.stats().total());
            var next = sub.borrowObjectHolder(); assertEquals(3, pool.stats().total());
            pool.reusable = true; next.close(); sub.close();
        }
    }

    @Test void patrolShrinksOnlyMainIdleDownToMin() {
        var clock = new AtomicLong();
        var cfg = ContainerConfig.builder().initial(4).min(1).max(4).initialObjectsPerSub(2)
                .poolIdleLimit(Duration.ofNanos(10)).build();
        try (var pool = new Pool(cfg, null, clock)) {
            pool.start(); var sub = pool.borrowContainer(); clock.set(10); pool.patrol();
            assertEquals(2, pool.stats().total()); assertEquals(2, pool.stats().subStack());
            sub.close(); clock.set(20); pool.patrol();
            assertEquals(1, pool.stats().total()); assertEquals(1, pool.stats().mainIdle());
            assertEquals(3, pool.destroyed.get());
        }
    }

    @Test void parentExpirySuppressesChildEventsAndOldTokensCannotReachAnotherSub() {
        var clock = new AtomicLong(); var events = new ArrayList<LeakEvent>();
        var cfg = ContainerConfig.builder().initial(2).min(0).max(2).initialObjectsPerSub(1)
                .subBorrowLimit(Duration.ofNanos(10)).holderBorrowLimit(Duration.ofNanos(10)).build();
        try (var logs = new Logs(); var pool = new Pool(cfg, event -> { events.add(event); event.forceClose(); }, clock)) {
            pool.start(); var oldSub = pool.borrowContainer(); var lease = oldSub.borrowObjectHolder();
            clock.set(10); assertEquals(1, pool.patrol().detected());
            assertEquals(LeakEvent.Target.SUB, events.get(0).target());
            assertEquals(1, pool.destroyed.get()); assertFalse(lease.recycle());
            var newSub = pool.borrowContainer(); var current = newSub.borrowObjectHolder();
            assertThrows(IllegalStateException.class, oldSub::borrowObjectHolder);
            assertThrows(IllegalStateException.class, oldSub::patrol);
            assertFalse(events.get(0).forceClose());
            current.use(v -> v.append("current")); current.close(); oldSub.close(); newSub.close();
        }
    }

    @Test void subIdleTouchDoesNotExtendBorrowLimitAndSubPatrolChecksChildrenOnly() {
        var clock = new AtomicLong(); var events = new ArrayList<LeakEvent>();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .subBorrowLimit(Duration.ofNanos(20)).subIdleLimit(Duration.ofNanos(10))
                .holderIdleLimit(Duration.ofNanos(10)).build();
        try (var pool = new Pool(cfg, events::add, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            clock.set(9); sub.touch(); clock.set(10); sub.patrol();
            assertEquals(1, events.size()); assertEquals(LeakEvent.Target.HOLDER, events.get(0).target());
            lease.touch(); clock.set(18); assertEquals(0, pool.patrol().detected());
            clock.set(20); pool.patrol();
            assertTrue(events.stream().anyMatch(e -> e.target() == LeakEvent.Target.SUB
                    && e.reason() == LeakEvent.Reason.BORROW_AND_IDLE_LIMIT));
            lease.close(); sub.close();
        }
    }

    @Test void listenerExceptionDoesNotForceCloseAndIsReportedAgainOnNextScan() {
        var clock = new AtomicLong(); var calls = new AtomicInteger();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(10)).build();
        try (var logs = new Logs(); var pool = new Pool(cfg, event -> {
            calls.incrementAndGet(); throw new IllegalArgumentException("listener");
        }, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder(); clock.set(10);
            assertEquals(1, pool.patrol().failures()); assertEquals(1, pool.patrol().failures());
            assertEquals(2, calls.get()); assertEquals(0, pool.destroyed.get());
            lease.use(v -> v.append("still valid")); lease.close(); sub.close();
        }
    }

    @Test void deferredEventMayCloseSameLeaseAfterTouchButNotTwice() {
        var clock = new AtomicLong(); var events = new ArrayList<LeakEvent>();
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderIdleLimit(Duration.ofNanos(10)).captureBorrowStack(true).build();
        try (var logs = new Logs(); var pool = new Pool(cfg, events::add, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder(); clock.set(10); pool.patrol();
            lease.touch(); var event = events.get(0); assertFalse(event.borrowStack().isEmpty());
            assertTrue(event.forceClose()); assertFalse(event.forceClose()); assertFalse(lease.recycle());
            assertEquals(1, pool.destroyed.get()); sub.close();
        }
    }

    @Test void customSubOnBorrowRunsOnceAndCannotReenterPublicLifetimeOperations() {
        var borrowed = new AtomicInteger();
        try (var pool = new Pool(config(1, 0, 2, 1)) {
            @Override protected SubContainer<StringBuilder> createSubContainer() {
                return new SubContainer<>(this) {
                    @Override protected void onBorrow() {
                        borrowed.incrementAndGet(); assertThrows(IllegalStateException.class, this::borrowObjectHolder);
                        assertThrows(IllegalStateException.class, this::close);
                    }
                };
            }
        }) {
            pool.start(); var sub = pool.borrowContainer();
            sub.borrowObjectHolder().close(); sub.borrowObjectHolder().close(); sub.close();
            assertEquals(1, borrowed.get());
            pool.borrowContainer().close(); assertEquals(2, borrowed.get());
        }
    }

    @Test void failedOnBorrowDestroysEntirePreparationAndReleasesSubCapacity() {
        try (var pool = new Pool(config(2, 0, 2, 2)) {
            @Override protected SubContainer<StringBuilder> createSubContainer() {
                return new SubContainer<>(this) {
                    @Override protected void onBorrow() { throw new IllegalArgumentException("prepare"); }
                };
            }
        }) {
            pool.start(); var error = assertThrows(IllegalArgumentException.class, pool::borrowContainer);
            assertEquals("prepare", error.getMessage()); assertEquals(2, pool.destroyed.get());
            assertEquals(0, pool.stats().total()); assertEquals(0, pool.stats().activeSubs());
            assertEquals(0, pool.stats().revokedSubs());
        }
    }

    @Test void factoryNullAndSharedValueAreRejectedWithoutDestroyingAnExistingValue() {
        try (var pool = new Pool(config(0, 0, 2, 0)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() { return null; }
        }) {
            pool.start(); var sub = pool.borrowContainer();
            assertThrows(ObjectCreationException.class, sub::borrowObjectHolder);
            assertEquals(0, pool.stats().creating()); sub.close();
        }
        var shared = new StringBuilder();
        try (var pool = new Pool(config(1, 0, 2, 0)) {
            @Override protected ObjectHolder<StringBuilder> createNewHolder() { return new ObjectHolder<>(shared); }
        }) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertThrows(ObjectCreationException.class, sub::borrowObjectHolder);
            lease.use(v -> assertSame(shared, v)); assertEquals(0, pool.destroyed.get());
            assertEquals(1, pool.stats().total()); assertEquals(0, pool.stats().creating()); lease.close(); sub.close();
        }
    }

    @Test void foreignMainRecyclingIsRejectedWithoutAffectingOriginalLease() {
        try (var a = new Pool(config(1, 0, 1, 1)); var b = new Pool(config(0, 0, 1, 0))) {
            a.start(); b.start(); var sub = a.borrowContainer(); var lease = sub.borrowObjectHolder();
            assertThrows(IllegalArgumentException.class, () -> b.recycleContainer(sub));
            assertThrows(IllegalArgumentException.class, () -> b.newObject(sub));
            lease.use(v -> v.append("valid")); lease.close(); sub.close();
        }
    }

    @Test void mismatchWarningIsEmittedOnceAndIncludesExactSnapshot() {
        try (var logs = new Logs(); var pool = new Pool(config(1, 0, 1, 1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            sub.close(); sub.close(); lease.close();
            assertEquals(1, logs.messages.size());
            assertTrue(logs.messages.get(0).contains("borrow=1, recycle=0, difference=1, forced=0, outstanding=1, pending=0"));
        }
        try (var logs = new Logs(); var pool = new Pool(config(1, 0, 1, 1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            lease.close(); lease.close(); sub.close(); assertTrue(logs.messages.isEmpty());
        }
    }

    @Test void disabledLimitsAndNanoTimeWrapDoNotCauseEarlyExpiry() {
        var clock = new AtomicLong(Long.MAX_VALUE - 5);
        var cfg = ContainerConfig.builder().initial(1).min(0).max(1).initialObjectsPerSub(1)
                .holderBorrowLimit(Duration.ofNanos(10)).build();
        try (var pool = new Pool(cfg, event -> {}, clock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            clock.set(Long.MIN_VALUE + 3); assertEquals(0, pool.patrol().detected());
            clock.set(Long.MIN_VALUE + 4); assertEquals(1, pool.patrol().detected()); lease.close(); sub.close();
        }
        var disabledClock = new AtomicLong();
        try (var pool = new Pool(config(1, 0, 1, 1), null, disabledClock)) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            disabledClock.set(Long.MAX_VALUE); assertEquals(0, pool.patrol().detected()); lease.close(); sub.close();
        }
    }

    static final class Logs extends Handler implements AutoCloseable {
        final Logger logger = Logger.getLogger(MainContainer.class.getName());
        final boolean previous = logger.getUseParentHandlers();
        final List<String> messages = new CopyOnWriteArrayList<>();
        Logs() { logger.setUseParentHandlers(false); logger.addHandler(this); }
        @Override public void publish(LogRecord record) {
            messages.add(new SimpleFormatter().formatMessage(record));
        }
        @Override public void flush() {}
        @Override public void close() { logger.removeHandler(this); logger.setUseParentHandlers(previous); }
    }
}
