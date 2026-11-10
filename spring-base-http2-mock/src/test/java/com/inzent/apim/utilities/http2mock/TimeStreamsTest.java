package com.inzent.apim.utilities.http2mock;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class TimeStreamsTest {
    @Test void cancellationRemovesPendingTask() {
        var scheduler = scheduler();
        try {
            var subscription = new TimeStreams(scheduler).open(event -> {
                throw new AssertionError("cancelled stream must not emit");
            }, () -> { throw new AssertionError("cancel is not normal completion"); }, error -> { });
            subscription.cancel();
            subscription.cancel();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        } finally { scheduler.shutdown(); }
    }

    @Test void sendFailureCancelsTaskAndReportsError() throws Exception {
        var scheduler = scheduler();
        try {
            var failed = new CountDownLatch(1);
            var sends = new AtomicInteger();
            new TimeStreams(scheduler).open(event -> {
                sends.incrementAndGet(); throw new IOException("disconnected");
            }, () -> { throw new AssertionError("failed stream must not complete normally"); }, error -> {
                assertThat(error).isInstanceOf(IOException.class); failed.countDown();
            });
            assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(sends).hasValue(1);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        } finally { scheduler.shutdown(); }
    }

    private ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
