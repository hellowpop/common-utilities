package com.inzent.apim.utilities.http.curl;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceSafetyTest {
    @TempDir Path temp;

    @Test void closedReadersHaveConsistentMarkResetBehaviorWithoutNpe() throws Exception {
        try (var context = context().build()) {
            BodyStore body = context.newBody(); body.copyFrom(new ByteArrayInputStream(new byte[]{1,2,3}));
            InputStream reader = body.openStream();
            reader.mark(3); assertEquals(1,reader.read()); reader.reset(); assertEquals(1,reader.read());
            reader.close();
            assertThrows(IOException.class,reader::reset);
            assertFalse(reader.markSupported());
            assertDoesNotThrow(() -> reader.mark(1));
        }
    }

    @Test void uncheckedReaderCloseFailuresDoNotPreventOtherReadersClosing() throws Exception {
        TrackedStreams streams = new TrackedStreams();
        RuntimeException first = new IllegalStateException("close");
        AtomicBoolean otherClosed = new AtomicBoolean();
        AtomicBoolean recover = new AtomicBoolean();
        AtomicInteger attempted = new AtomicInteger();
        for (int i=0;i<2;i++) streams.track(new ByteArrayInputStream(new byte[]{1}) {
            @Override public void close() { attempted.incrementAndGet(); if (!recover.get()) throw first; }
        });
        InputStream other = streams.track(new ByteArrayInputStream(new byte[]{2}) {
            @Override public void close() { otherClosed.set(true); }
        });
        assertSame(first,assertThrows(IllegalStateException.class,streams::close));
        assertEquals(2,attempted.get());
        assertTrue(otherClosed.get());
        assertThrows(IOException.class,other::read);
        recover.set(true); streams.close();
    }

    @Test void failedBodySourceDoesNotBecomeCapturedAndCanBeRetriedForNull() throws Exception {
        try (BodyStore body = new BodyStore(temp)) {
            assertThrows(NullPointerException.class, () -> body.copyFrom(null));
            body.copyFrom(new ByteArrayInputStream(new byte[]{1,2}));
            try (InputStream in = body.openStream()) { assertArrayEquals(new byte[]{1,2},in.readAllBytes()); }
        }
    }

    @Test void nullHeaderBlockStatusIsRejectedAtPublicBoundary() {
        assertThrows(NullPointerException.class, () -> new ResponseRecord.HeaderBlock(null,new DefaultHttpHeaders()));
    }

    @Test void missingUrlIsRecordedAsValidationErrorInsteadOfInternalNpe() throws Exception {
        try (var context = ExecuteContext.builder().build()) {
            var error = assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(context));
            assertInstanceOf(IllegalArgumentException.class,error.getCause());
            assertEquals(StageRecord.Status.FAILED,context.record().stage(ExecutionRecord.Stage.VALIDATE).status());
            assertTrue(context.record().stdout().isEmpty());
        }
    }

    @Test void closedBodyAndReaderReleasePayloadReferences() throws Exception {
        BodyStore body = new BodyStore(temp);
        body.copyFrom(new ByteArrayInputStream(new byte[1024]));
        InputStream reader = body.openStream(); body.close();
        // Object graph evidence is deterministic; no GC timing or heap-size assumptions.
        assertNull(field(BodyStore.class,"memory").get(body));
        assertThrows(IOException.class,reader::read);
        Field manager = field(BodyStore.class,"readers");
        assertTrue(((Set<?>)field(TrackedStreams.class,"readers").get(manager.get(body))).isEmpty());
    }

    @Test void contextCleansOtherResourcesAfterErrorAndRetriesFailedResource() throws Exception {
        try (var context = context().build()) {
            ProcessOutputRecord output = context.newOutput("stdout.bin");
            AtomicBoolean recover = new AtomicBoolean();
            AssertionError failure = new AssertionError("cleanup fixture");
            @SuppressWarnings("unchecked") List<Closeable> resources = (List<Closeable>)field(ExecuteContext.class,"resources").get(context);
            resources.add(() -> { if (!recover.get()) throw failure; });
            try {
                assertSame(failure,assertThrows(AssertionError.class,context::close));
                assertFalse(Files.exists(output.path()));
                assertEquals(1,resources.size());
            } finally { recover.set(true); context.close(); }
            assertTrue(resources.isEmpty());
        }
    }

    @Test void workerStartFailureStopsCurlBeforePostHook() throws Exception {
        AtomicReference<Process> process = new AtomicReference<>();
        AtomicBoolean post = new AtomicBoolean();
        AssertionError failedStart = new AssertionError("worker start fixture");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> { try { exchange.getRequestBody().transferTo(OutputStream.nullOutputStream()); } finally { exchange.close(); } });
        server.start();
        try (var context = context().url(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"))
                .responseTimeout(Duration.ofSeconds(5)).build()) {
            ThreadFactory factory = task -> new Thread(task) {
                @Override public synchronized void start() {
                    try { process.set((Process)field(ExecuteContext.class,"process").get(context)); }
                    catch (Exception e) { throw new AssertionError(e); }
                    throw failedStart;
                }
            };
            context.setPostExecute(() -> { post.set(true); assertFalse(process.get().isAlive()); });
            assertSame(failedStart,assertThrows(AssertionError.class, () -> new CurlHttpMockExecutor(factory).handle(context)));
            assertTrue(post.get()); assertFalse(process.get().isAlive());
            assertEquals(0,failedStart.getSuppressed().length);
        } finally { server.stop(0); }
    }

    @Test void processStreamErrorDoesNotPreventOtherStreamsOrFilesClosing() throws Exception {
        try (var context = context().build()) {
            ProcessOutputRecord output = context.newOutput("stdout.bin");
            AssertionError error = new AssertionError("process stream close");
            AtomicBoolean recover = new AtomicBoolean(); AtomicInteger inputClosed = new AtomicInteger(), errorClosed = new AtomicInteger();
            Process process = new Process() {
                private final OutputStream stdin = new ByteArrayOutputStream() {
                    @Override public void close() { if (!recover.get()) throw error; }
                };
                @Override public OutputStream getOutputStream() { return stdin; }
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]) {
                    @Override public void close() { inputClosed.incrementAndGet(); }
                }; }
                @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]) {
                    @Override public void close() { errorClosed.incrementAndGet(); }
                }; }
                @Override public int waitFor() { return 0; }
                @Override public int exitValue() { return 0; }
                @Override public void destroy() { }
            };
            context.process(process);
            try {
                assertSame(error,assertThrows(AssertionError.class,context::close));
                assertEquals(1,inputClosed.get()); assertEquals(1,errorClosed.get());
                assertFalse(Files.exists(output.path()));
            } finally { recover.set(true); context.close(); }
            assertNull(field(ExecuteContext.class,"process").get(context));
        }
    }

    @Test void nullPublicInputsFailAtTheirBoundaryWithoutResources() throws Exception {
        assertThrows(NullPointerException.class, () -> new CurlHttpMockExecutor().handle(null));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().configure(null));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().url(null));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().method(null));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().contentType(null));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().contentEncoding(null));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().responseTimeout(null));
        assertThrows(NullPointerException.class, () -> ExecuteConfigure.builder().additionalArguments(null));
        assertThrows(NullPointerException.class, () -> ExecuteConfigure.builder().additionalArguments(Arrays.asList((String)null)));
        assertThrows(NullPointerException.class, () -> new ResponseRecord.HeaderBlock(HttpResponseStatus.OK,null));
        try (var context = context().build()) {
            assertThrows(NullPointerException.class, () -> context.setPreExecute(null));
            assertThrows(NullPointerException.class, () -> context.setPostExecute(null));
            assertTrue(context.record().stdout().isEmpty());
        }
        try (var files = Files.list(temp)) { assertEquals(0,files.count()); }
    }

    @Test void processAndHooksAreNotRetainedAfterClosedExecution() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> { try { exchange.getRequestBody().transferTo(OutputStream.nullOutputStream()); exchange.sendResponseHeaders(204,-1); } finally { exchange.close(); } });
        server.start();
        try (var context = context().url(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"))
                .preExecute(() -> { }).postExecute(() -> { }).build()) {
            new CurlHttpMockExecutor().handle(context);
            assertNull(field(ExecuteContext.class,"preExecute").get(context));
            assertNull(field(ExecuteContext.class,"postExecute").get(context));
            assertNull(field(ExecuteContext.class,"owner").get(context));
            assertNull(field(ExecuteContext.class,"inputWorker").get(context));
            context.close();
            assertNull(field(ExecuteContext.class,"process").get(context));
            assertTrue(((List<?>)field(ExecuteContext.class,"resources").get(context)).isEmpty());
        } finally { server.stop(0); }
    }

    @Test void threadInterruptFailureDoesNotSkipFileCleanup() throws Exception {
        try (var context = context().build()) {
            ProcessOutputRecord output = context.newOutput("stdout.bin");
            AssertionError failure = new AssertionError("interrupt fixture");
            AtomicBoolean recover = new AtomicBoolean();
            context.inputWorker(new Thread() {
                @Override public void interrupt() { if (!recover.get()) throw failure; super.interrupt(); }
            });
            try {
                assertSame(failure,assertThrows(AssertionError.class,context::close));
                assertFalse(Files.exists(output.path()));
            } finally { recover.set(true); context.close(); }
        }
    }

    @Test void interruptedCloseStillJoinsWorkerAndRestoresInterrupt() throws Exception {
        ExecuteContext context = context().build();
        CountDownLatch ready = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            ready.countDown();
            try { release.await(); } catch (InterruptedException expected) {
                interrupted.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
            }
        });
        AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean restored = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { context.close(); } catch (Throwable e) { failure.set(e); }
            restored.set(Thread.currentThread().isInterrupted());
        });
        worker.start(); assertTrue(ready.await(5,TimeUnit.SECONDS)); context.inputWorker(worker); closer.start();
        try {
            assertTrue(interrupted.await(5,TimeUnit.SECONDS));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while (closer.isAlive() && closer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            release.countDown(); closer.join(5000); worker.join(5000);
            assertFalse(closer.isAlive()); assertFalse(worker.isAlive());
            assertNull(failure.get()); assertTrue(restored.get());
            assertNull(field(ExecuteContext.class,"inputWorker").get(context));
        } finally { release.countDown(); worker.interrupt(); closer.join(5000); worker.join(5000); context.close(); }
    }

    @Test void bodyReadFailureSurvivesSimultaneousSpillCloseFailure() throws Exception {
        IOException sourceFailure = new IOException("source failed"), closeFailure = new IOException("spill close failed");
        AtomicBoolean diskClosed = new AtomicBoolean();
        try (BodyStore body = new BodyStore(temp,path -> new FilterOutputStream(new BufferedOutputStream(Files.newOutputStream(path))) {
            @Override public void close() throws IOException { super.close(); if (diskClosed.compareAndSet(false,true)) throw closeFailure; }
        })) {
            InputStream source = new InputStream() {
                private int remaining = 1048577;
                @Override public int read() throws IOException { throw new UnsupportedOperationException(); }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (remaining == 0) throw sourceFailure;
                    int count = Math.min(remaining,length); Arrays.fill(buffer,offset,offset+count,(byte)1); remaining -= count; return count;
                }
            };
            IOException actual = assertThrows(IOException.class, () -> body.copyFrom(source));
            assertSame(sourceFailure,actual);
            assertArrayEquals(new Throwable[]{closeFailure},actual.getSuppressed());
            assertTrue(diskClosed.get());
        }
        try (var files = Files.list(temp)) { assertEquals(0,files.count()); }
    }

    @Test void failedSpillWriterCloseIsRetainedForContextCleanupRetry() throws Exception {
        IOException closeFailure = new IOException("close before release");
        AtomicInteger closes = new AtomicInteger(); AtomicReference<OutputStream> stream = new AtomicReference<>();
        BodyStore body = new BodyStore(temp,path -> {
            OutputStream output = new BufferedOutputStream(Files.newOutputStream(path)) {
                @Override public void close() throws IOException {
                    if (closes.incrementAndGet() == 1) throw closeFailure;
                    super.close();
                }
            };
            stream.set(output); return output;
        });
        try {
            assertSame(closeFailure,assertThrows(IOException.class, () -> body.copyFrom(new ByteArrayInputStream(new byte[1048577]))));
            body.close();
            assertEquals(2,closes.get());
            try (var files = Files.list(temp)) { assertEquals(0,files.count()); }
        } finally { if (stream.get() != null) stream.get().close(); body.close(); }
    }

    ExecuteContext.Builder context() {
        return ExecuteContext.builder().url(URI.create("http://127.0.0.1/"))
            .configure(ExecuteConfigure.builder().temporaryDirectory(temp).build());
    }
    static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
}
