package com.inzent.apim.utilities.recycle;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.inzent.apim.utilities.recycle.MainContainerTest.config;

class Java17SafetyTest {
    @Test void overridingThreadIdCannotImpersonateBorrowingThread() throws Exception {
        try (var pool = new MainContainerTest.Pool(config(1,0,1,1))) {
            pool.start(); var sub = pool.borrowContainer();
            long ownerId = Thread.currentThread().getId();
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            Thread impostor = new Thread(() -> {
                try { sub.stats(); } catch (Throwable e) { rejected.set(e); }
            }) { @Override public long getId() { return ownerId; } };
            impostor.start(); impostor.join(5000);
            assertFalse(impostor.isAlive());
            assertInstanceOf(IllegalStateException.class,rejected.get());
            sub.close();
        }
    }

    @Test void actualOwnerRemainsValidWhenOverriddenIdChanges() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong id = new AtomicLong(1);
        Thread owner = new Thread(() -> {
            try (var pool = new MainContainerTest.Pool(config(1,0,1,1))) {
                pool.start(); var sub = pool.borrowContainer();
                id.incrementAndGet();
                var lease = sub.borrowObjectHolder(); lease.close(); sub.close();
            } catch (Throwable e) { failure.set(e); }
        }) { @Override public long getId() { return id.get(); } };
        owner.start(); owner.join(5000);
        assertFalse(owner.isAlive()); assertNull(failure.get());
    }

    @Test void overridingThreadIdDoesNotReturnHolderDirectlyToOwnerStack() throws Exception {
        try (var pool = new MainContainerTest.Pool(config(1,0,1,1))) {
            pool.start(); var sub = pool.borrowContainer(); var lease = sub.borrowObjectHolder();
            long ownerId = Thread.currentThread().getId();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { lease.close(); } catch (Throwable e) { failure.set(e); } }) {
                @Override public long getId() { return ownerId; }
            };
            worker.start(); worker.join(5000); assertFalse(worker.isAlive()); assertNull(failure.get());
            assertEquals(1,pool.stats().subReturns());
            sub.close();
        }
    }
}
