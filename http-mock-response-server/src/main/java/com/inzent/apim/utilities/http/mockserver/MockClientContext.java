package com.inzent.apim.utilities.http.mockserver;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Connection-scoped API. Access is restricted to the executing client worker. */
public final class MockClientContext {
    @FunctionalInterface public interface PointOperation { void run() throws Exception; }
    private final ClientSession session;
    private final Thread owner;
    private final byte[] buffer;
    private int lastRead;
    private long totalBytes;
    private String point;

    MockClientContext(ClientSession session) {
        this.session = session;
        this.owner = Thread.currentThread();
        this.buffer = new byte[session.binding.options.readChunkSize()];
    }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Context belongs to its client worker");
    }
    public void runPoint(String name, PointOperation operation) throws Exception {
        requireOwner();
        if (Objects.requireNonNull(name).isBlank()) throw new IllegalArgumentException("point name is blank");
        Objects.requireNonNull(operation);
        checkCancelled();
        String previous = point;
        point = name;
        try {
            session.event(ServiceEventType.POINT_ENTER, null, false, 0, null);
            operation.run();
            session.event(ServiceEventType.POINT_EXIT, null, false, 0, null);
        } catch (Exception | Error failure) {
            session.failedPoint = name;
            throw failure;
        } finally { point = previous; }
    }
    public int readChunk() throws Exception {
        checkCancelled();
        lastRead = session.socket.getInputStream().read(buffer);
        if (lastRead > 0) {
            totalBytes += lastRead;
            session.event(ServiceEventType.REQUEST_RECEIVED, null, false, lastRead, null);
        }
        return lastRead;
    }
    /** The view is overwritten on the next read; copy bytes to retain them. */
    public ByteBuffer chunk() {
        requireOwner();
        return ByteBuffer.wrap(buffer, 0, Math.max(lastRead, 0)).slice().asReadOnlyBuffer();
    }
    public void delay(Duration duration) throws InterruptedException {
        checkCancelled();
        long nanos = Durations.nanos(duration, "duration", true);
        if (nanos != 0) TimeUnit.NANOSECONDS.sleep(nanos);
        checkCancelled();
    }
    public void checkCancelled() throws InterruptedException {
        requireOwner();
        if (session.cancelled() || Thread.currentThread().isInterrupted())
            throw new InterruptedException("Client session cancelled");
    }
    public MockOptions options() { requireOwner(); return session.binding.options; }
    public long clientId() { requireOwner(); return session.id; }
    public int lastRead() { requireOwner(); return lastRead; }
    public long totalBytes() { requireOwner(); return totalBytes; }
    public String point() { requireOwner(); return point; }
}
