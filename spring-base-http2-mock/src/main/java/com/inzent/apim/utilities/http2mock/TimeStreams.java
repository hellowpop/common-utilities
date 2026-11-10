package com.inzent.apim.utilities.http2mock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

@Service
public class TimeStreams {
    private final ThreadPoolTaskScheduler scheduler;

    public TimeStreams(ThreadPoolTaskScheduler scheduler) { this.scheduler = scheduler; }

    public record TimeEvent(int sequence, Instant serverTime) { }
    @FunctionalInterface public interface Sink { void send(TimeEvent event) throws Exception; }

    public Subscription open(Sink sink, Runnable complete, Consumer<Exception> failure) {
        Subscription subscription = new Subscription(sink, complete, failure);
        subscription.attach(scheduler.scheduleAtFixedRate(subscription::tick,
                Instant.now().plusSeconds(1), Duration.ofSeconds(1)));
        return subscription;
    }

    public static final class Subscription {
        private final Sink sink;
        private final Runnable complete;
        private final Consumer<Exception> failure;
        private ScheduledFuture<?> future;
        private boolean cancelled;
        private int sequence;

        private Subscription(Sink sink, Runnable complete, Consumer<Exception> failure) {
            this.sink = sink; this.complete = complete; this.failure = failure;
        }
        private synchronized void attach(ScheduledFuture<?> future) {
            this.future = future;
            if (cancelled) future.cancel(false);
        }
        public synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(false);
        }
        private synchronized void tick() {
            if (cancelled) return;
            try {
                sink.send(new TimeEvent(++sequence, Instant.now()));
                if (sequence == 10) { cancel(); complete.run(); }
            } catch (Exception error) {
                cancel(); failure.accept(error);
            }
        }
    }

    @Configuration
    static class SchedulerConfiguration {
        @Bean
        ThreadPoolTaskScheduler timeStreamScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(4);
            scheduler.setThreadNamePrefix("mock-time-");
            scheduler.setRemoveOnCancelPolicy(true);
            return scheduler;
        }
    }
}
