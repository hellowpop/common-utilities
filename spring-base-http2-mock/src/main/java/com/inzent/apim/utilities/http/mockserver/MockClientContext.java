package com.inzent.apim.utilities.http.mockserver;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Connection-scoped API, restricted to its client worker through terminal callbacks.
 * After completion, owned references are released and operations reject access.
 */
public final class MockClientContext {
    @FunctionalInterface public interface PointOperation { void run() throws Exception; }
    private ClientSession session;
    private Thread owner;
    private byte[] buffer;
    private volatile boolean released;
    private int lastRead;
    private long totalBytes;
    private final AtomicReference<Enum<?>> point = new AtomicReference<>();
    private enum ReadMode { RAW, HTTP }
    private final AtomicReference<ReadMode> readMode = new AtomicReference<>();
    private HttpRequestDecoder decoder;
    private HttpProtocolLimits protocolLimits;

    MockClientContext(ClientSession session) {
        this.session = session;
        this.owner = Thread.currentThread();
        this.buffer = new byte[session.binding.options.readChunkSize()];
    }
    private void requireOwner() {
        if (released) throw new IllegalStateException("Client context has ended");
        if (Thread.currentThread() != owner) throw new IllegalStateException("Context belongs to its client worker");
    }
    /** Called by the owning worker after terminal callbacks, including exceptional completion. */
    void release() {
        if (released) return;
        requireOwner();
        released = true;
        decoder = null;
        protocolLimits = null;
        buffer = null;
        session = null;
        owner = null;
        point.getAndSet(null);
        readMode.getAndSet(null);
    }
    /** Execute a default operation with point events and restoration. HTTP results use httpProtocol(). */
    public void runPoint(MockPoint point) throws Exception {
        runPoint(point, () -> {
            switch (point) {
                case ON_CONNECTED, BEFORE_READ, ON_CHUNK_RECEIVED, ON_PEER_EOF -> checkCancelled();
                case READ_CHUNK -> readChunk();
                case DELAY_BEFORE_NEXT_READ -> delay(options().readInterval());
                case READ_HTTP_PROTOCOL -> readHttpProtocol();
                default -> throw new IllegalArgumentException("Unsupported mock point: " + point);
            }
        });
    }
    public void runPoint(Enum<?> name, PointOperation operation) throws Exception {
        requireOwner();
        Objects.requireNonNull(name, "point");
        Objects.requireNonNull(operation);
        checkCancelled();
        Enum<?> previous = point.getAndSet(name);
        try {
            session.event(ServiceEventType.POINT_ENTER, null, false, 0, null);
            operation.run();
            session.event(ServiceEventType.POINT_EXIT, null, false, 0, null);
        } catch (Exception | Error failure) {
            session.failedPoint = name;
            throw failure;
        } finally { point.getAndSet(previous); }
    }
    public int readChunk() throws Exception {
        selectReadMode(ReadMode.RAW);
        return readBytes();
    }
    private int readBytes() throws Exception {
        checkCancelled();
        lastRead = session.socket.getInputStream().read(buffer);
        if (lastRead > 0) {
            totalBytes += lastRead;
            session.event(ServiceEventType.REQUEST_RECEIVED, null, false, lastRead, null);
        }
        return lastRead;
    }
    private void selectReadMode(ReadMode mode) {
        requireOwner();
        ReadMode previous = readMode.compareAndExchange(null, mode);
        if (previous != null && previous != mode)
            throw new IllegalStateException("Raw and HTTP protocol reads cannot be mixed");
    }
    /** Read once and interpret every byte, retaining incomplete sections for the next call. */
    public HttpProtocolResult readHttpProtocol() throws Exception {
        return readHttpProtocol(protocolLimits == null ? HttpProtocolLimits.defaults() : protocolLimits);
    }
    public HttpProtocolResult readHttpProtocol(HttpProtocolLimits limits) throws Exception {
        requireOwner();
        Objects.requireNonNull(limits, "limits");
        if (protocolLimits != null && !protocolLimits.equals(limits))
            throw new IllegalStateException("HTTP protocol limits cannot change during a connection");
        selectReadMode(ReadMode.HTTP);
        if (decoder == null) { protocolLimits = limits; decoder = new HttpRequestDecoder(limits); }
        if (decoder.result().endOfInput()) return decoder.result();
        decoder.requireOpen();
        int count = readBytes();
        return count < 0 ? decoder.endOfInput() : decoder.feed(chunk());
    }
    /** Inspect the last result without reading the socket. */
    public HttpProtocolResult httpProtocol() {
        requireOwner();
        return decoder == null ? HttpProtocolResult.empty() : decoder.result();
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
    public Enum<?> point() { requireOwner(); return point.get(); }
}
