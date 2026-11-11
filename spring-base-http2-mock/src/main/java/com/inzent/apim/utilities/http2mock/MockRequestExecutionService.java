package com.inzent.apim.utilities.http2mock;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@EnableConfigurationProperties(MockExecutionProperties.class)
public class MockRequestExecutionService {
    private final MockExecutionProperties limits;
    private final Semaphore admission;
    private final ThreadPoolExecutor workers, coordinators;
    private final ScheduledThreadPoolExecutor deadlines;
    private final MockHttpTransport transport;
    private final ConcurrentMap<String,Job> jobs = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MockRequestExecutionService(MockExecutionProperties limits) {
        this.limits = limits; admission = new Semaphore(limits.maxConcurrentJobs());
        int count = limits.maxThreadCount() * limits.maxConcurrentJobs();
        workers = pool(count, "mock-request-"); coordinators = pool(limits.maxConcurrentJobs(), "mock-sse-");
        deadlines = new ScheduledThreadPoolExecutor(2, threads("mock-deadline-")); deadlines.setRemoveOnCancelPolicy(true);
        transport = new MockHttpTransport(limits, deadlines);
    }
    private static ThreadFactory threads(String prefix) {
        var sequence = new AtomicInteger();
        return task -> { Thread thread = new Thread(task, prefix + sequence.incrementAndGet()); thread.setDaemon(true); return thread; };
    }
    private static ThreadPoolExecutor pool(int size, String prefix) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(size), threads(prefix));
    }
    public SseEmitter execute(ExecuteMockRequest request) {
        long total = request.validate(limits);
        if (closed) throw new IllegalStateException("Request executor is shutting down");
        if (!admission.tryAcquire()) throw new RejectedExecutionException("Maximum concurrent execution jobs reached");
        Job job = new Job(request, total);
        jobs.put(job.id, job);
        job.emitter.onCompletion(job::disconnected);
        job.emitter.onError(error -> job.disconnected());
        job.emitter.onTimeout(() -> { job.disconnected(); job.emitter.complete(); });
        try {
            job.timeout = deadlines.schedule(() -> job.cancel("JOB_TIMEOUT"), limits.jobTimeout().toMillis(), TimeUnit.MILLISECONDS);
            coordinators.execute(job);
        } catch (RejectedExecutionException rejected) {
            job.cancel("SERVER_STOP"); job.release(); throw new IllegalStateException("Request executor is shutting down", rejected);
        }
        return job.emitter;
    }
    int activeJobCount() { return jobs.size(); }
    private record EndpointResponse(String endpointUrl, Integer status) { }

    private final class Job implements Runnable {
        final String id = UUID.randomUUID().toString();
        final ExecuteMockRequest spec;
        final long total, combinations, started = System.nanoTime();
        final SseEmitter emitter = new SseEmitter(limits.jobTimeout().toMillis() + 30000L);
        final AtomicBoolean cancelled = new AtomicBoolean(), finished = new AtomicBoolean(), released = new AtomicBoolean();
        final AtomicLong attempted = new AtomicLong();
        final BlockingQueue<MockHttpTransport.Result> results = new ArrayBlockingQueue<>(limits.eventQueueCapacity());
        final Set<MockHttpTransport.Call> calls = ConcurrentHashMap.newKeySet();
        final CountDownLatch done;
        final List<Worker> tasks;
        final MockExecutionSummary summary = new MockExecutionSummary(limits.maxSummaryGroups());
        volatile boolean clientGone;
        volatile String cancellationReason;
        volatile ScheduledFuture<?> timeout;
        Job(ExecuteMockRequest spec, long total) {
            this.spec = spec; this.total = total; combinations = spec.combinationCount(); done = new CountDownLatch(spec.threadCount());
            var tasks = new ArrayList<Worker>();
            for (int thread = 1; thread <= spec.threadCount(); thread++) tasks.add(new Worker(thread));
            this.tasks = List.copyOf(tasks);
        }
        void disconnected() {
            if (finished.get()) return;
            clientGone = true; cancel("CLIENT_DISCONNECTED");
        }
        void cancel(String reason) {
            if (finished.get() || !cancelled.compareAndSet(false, true)) return;
            cancellationReason = reason;
            for (Worker task : tasks) { task.cancel(true); task.runIfNotStarted(); }
            for (var call : calls) call.abort(false);
        }
        void release() {
            if (!released.compareAndSet(false, true)) return;
            if (timeout != null) timeout.cancel(false);
            jobs.remove(id); workers.purge(); admission.release();
        }
        final class Worker extends FutureTask<Void> {
            final AtomicBoolean started = new AtomicBoolean();
            Worker(int thread) { super(() -> { work(thread); return null; }); }
            @Override public void run() {
                if (!started.compareAndSet(false, true)) return;
                try { super.run(); }
                finally { done.countDown(); }
            }
            void runIfNotStarted() { if (!started.get()) run(); }
        }
        void work(int thread) {
            try {
                for (long loop = 1; loop <= spec.loopCount() && !cancelled.get(); loop++) {
                  for (long combination = 0; combination < combinations && !cancelled.get(); combination++) {
                    var call = new MockHttpTransport.Call(); calls.add(call);
                    if (cancelled.get()) call.abort(false);
                    MockHttpTransport.Result result;
                    try { result = transport.execute(spec, spec.payloadAt(combination), combination + 1,
                            attempted.incrementAndGet(), thread, loop, call); }
                    finally { calls.remove(call); }
                    summary.add(result);
                    while (!cancelled.get() && !results.offer(result, 200, TimeUnit.MILLISECONDS)) { }
                  }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception | Error failure) {
                cancel("WORKER_FAILED");
            }
        }
        void emit(MockHttpTransport.Result result) throws IOException {
            emitter.send(SseEmitter.event().name("response").data(new EndpointResponse(spec.endpointUrl(), result.status())));
        }
        @Override public void run() {
            long delivered = 0, lastHeartbeat = System.nanoTime();
            try {
                if (clientGone) return;
                emitter.send(SseEmitter.event().name("started").data(Map.of("jobId", id, "threadCount", spec.threadCount(),
                        "loopCount", spec.loopCount(), "combinationCount", combinations, "totalCount", total,
                        "endpointUrl", spec.endpointUrl(), "startedAt", Instant.now())));
                for (Worker task : tasks) workers.execute(task);
                while (done.getCount() > 0 || !results.isEmpty()) {
                    if (clientGone) break;
                    var result = results.poll(100, TimeUnit.MILLISECONDS);
                    if (result != null) { emit(result); delivered++; }
                    if (System.nanoTime() - lastHeartbeat >= limits.heartbeatInterval().toNanos()) {
                        emitter.send(SseEmitter.event().comment("keepalive")); lastHeartbeat = System.nanoTime();
                    }
                }
                if (!clientGone) {
                    emitter.send(SseEmitter.event().name("summary").data(summary.snapshot(id, total, attempted.get(), delivered,
                            started, cancelled.get(), cancellationReason)));
                    finished.set(true); emitter.complete();
                }
            } catch (Exception failed) { clientGone = true; cancel("STREAM_FAILED"); emitter.completeWithError(failed); }
            finally {
                if (!finished.get()) cancel("STREAM_CLOSED");
                boolean interrupted = false;
                while (done.getCount() > 0) {
                    try { done.await(); } catch (InterruptedException cancelled) { interrupted = true; cancel("SERVER_STOP"); }
                }
                finished.set(true); release();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
    @PreDestroy public void close() {
        closed = true;
        jobs.values().forEach(job -> job.cancel("SERVER_STOP"));
        workers.shutdownNow(); coordinators.shutdown(); deadlines.shutdownNow();
        try { if (!coordinators.awaitTermination(10, TimeUnit.SECONDS)) coordinators.shutdownNow(); }
        catch (InterruptedException interrupted) { coordinators.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
