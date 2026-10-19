package com.inzent.apim.utilities.http.curl;

import com.inzent.apim.utilities.http.payload.*;
import io.netty.handler.codec.http.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Single-use execution. Do not access it concurrently with handle; close after handle returns. */
public final class ExecuteContext implements Closeable {
    private final ExecuteConfigure configure;
    private final URI url;
    private final HttpMethod method;
    private final ContentType contentType;
    private final ContentEncoding contentEncoding;
    private final long contentLength, blockSize;
    private final boolean chunked;
    private final Duration responseTimeout;
    private final HttpHeaders headers;
    private final ExecutionRecord record = new ExecutionRecord();
    private final List<Closeable> resources = new ArrayList<>();
    private Runnable preExecute, postExecute;
    private Path directory;
    private boolean running, used, closed;
    private Thread owner;
    private Process process;
    private Thread inputWorker;

    private ExecuteContext(Builder b) {
        configure = b.configure; url = b.url; method = b.method; contentType = b.contentType;
        contentEncoding = b.contentEncoding; contentLength = b.contentLength; blockSize = b.blockSize;
        chunked = b.chunked; responseTimeout = b.responseTimeout; headers = b.headers.copy();
        preExecute = b.preExecute; postExecute = b.postExecute;
    }
    public static Builder builder() { return new Builder(); }
    public ExecuteConfigure configure() { return configure; }
    public URI url() { return url; }
    public HttpMethod method() { return method; }
    public ContentType contentType() { return contentType; }
    public ContentEncoding contentEncoding() { return contentEncoding; }
    public long contentLength() { return contentLength; }
    public long blockSize() { return blockSize; }
    public boolean chunked() { return chunked; }
    public Duration responseTimeout() { return responseTimeout; }
    public synchronized ExecutionRecord record() { checkThread(); return record; }
    public synchronized HttpHeaders requestHeaders() {
        checkThread();
        if (closed) throw new IllegalStateException("Context closed");
        return used && record.stage(ExecutionRecord.Stage.PRE).status() != StageRecord.Status.RUNNING
            ? RequestRecord.snapshot(headers) : headers;
    }
    synchronized void begin() {
        if (closed || used) throw new IllegalStateException("Context already executed or closed");
        used = true; running = true; owner = Thread.currentThread(); record.start();
    }
    synchronized void finish(Throwable failure) { record.finish(failure); running = false; owner = null; preExecute = null; postExecute = null; }
    private void checkThread() {
        if (running && owner != Thread.currentThread()) throw new IllegalStateException("Context is executing on another thread");
    }
    public synchronized void setPreExecute(Runnable v) { checkMutable(); preExecute = Objects.requireNonNull(v); }
    public synchronized void setPostExecute(Runnable v) { checkMutable(); postExecute = Objects.requireNonNull(v); }
    private void checkMutable() { if (closed || used) throw new IllegalStateException("Context no longer configurable"); }
    Runnable preExecute() { return preExecute; }
    Runnable postExecute() { return postExecute; }
    HttpHeaders mutableHeaders() { return headers; }
    synchronized Path directory() throws IOException {
        checkThread();
        if (closed) throw new IllegalStateException("Context closed");
        if (directory == null) directory = Files.createTempDirectory(configure.temporaryDirectory(), "curl-execution-");
        return directory;
    }
    synchronized BodyStore newBody() throws IOException { BodyStore body = new BodyStore(directory()); resources.add(body); return body; }
    synchronized BodyStore referenceBody(Path path) throws IOException { BodyStore body = BodyStore.reference(path); resources.add(body); return body; }
    synchronized ProcessOutputRecord newOutput(String name) throws IOException {
        Path path = Files.createFile(directory().resolve(name));
        ProcessOutputRecord output = new ProcessOutputRecord(path); resources.add(output); return output;
    }
    synchronized void process(Process value) { process = value; }
    synchronized void inputWorker(Thread value) { inputWorker = value; }

    @Override public synchronized void close() throws IOException {
        if (running) throw new IllegalStateException("Close after handle returns");
        closed = true; preExecute = null; postExecute = null; headers.clear();
        Throwable failure = null;
        if (process != null) {
            try { CurlProcess.stop(process); if (!process.isAlive()) process = null; }
            catch (Throwable e) { failure = Cleanup.merge(failure,e); }
        }
        if (inputWorker != null) {
            boolean interrupted = Thread.interrupted();
            try {
                inputWorker.interrupt();
                long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while (inputWorker.isAlive() && System.nanoTime() < deadline) {
                    try { inputWorker.join(Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (inputWorker.isAlive()) failure = Cleanup.merge(failure,new IOException("Curl stdin worker is still running"));
                else inputWorker = null;
            } catch (Throwable e) { failure = Cleanup.merge(failure,e); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        for (var iterator = resources.listIterator(resources.size()); iterator.hasPrevious();) {
            try { iterator.previous().close(); iterator.remove(); }
            catch (Throwable e) { failure = Cleanup.merge(failure,e); }
        }
        if (directory != null) {
            try { Files.deleteIfExists(directory); directory = null; }
            catch (Throwable e) { failure = Cleanup.merge(failure,e); }
        }
        Cleanup.rethrow(failure);
    }

    public static final class Builder {
        private ExecuteConfigure configure = ExecuteConfigure.defaults();
        private URI url;
        private HttpMethod method = HttpMethod.POST;
        private ContentType contentType = ContentType.APPLICATION_JSON;
        private ContentEncoding contentEncoding = ContentEncoding.IDENTITY;
        private long contentLength = 4096, blockSize = 128;
        private boolean chunked;
        private Duration responseTimeout = Duration.ofSeconds(30);
        private HttpHeaders headers = new DefaultHttpHeaders();
        private Runnable preExecute, postExecute;
        public Builder configure(ExecuteConfigure v) { configure = Objects.requireNonNull(v); return this; }
        public Builder url(URI v) { url = Objects.requireNonNull(v); return this; }
        public Builder method(HttpMethod v) { method = Objects.requireNonNull(v); return this; }
        public Builder contentType(ContentType v) { contentType = Objects.requireNonNull(v); return this; }
        public Builder contentEncoding(ContentEncoding v) { contentEncoding = Objects.requireNonNull(v); return this; }
        public Builder contentLength(long v) { contentLength = v; return this; }
        public Builder blockSize(long v) { blockSize = v; return this; }
        public Builder chunked(boolean v) { chunked = v; return this; }
        public Builder responseTimeout(Duration v) { responseTimeout = ExecuteConfigure.positive(v); return this; }
        public Builder preExecute(Runnable v) { preExecute = Objects.requireNonNull(v); return this; }
        public Builder postExecute(Runnable v) { postExecute = Objects.requireNonNull(v); return this; }
        public Builder requestHeader(CharSequence name, Object value) {
            headers.add(Objects.requireNonNull(name).toString(), Objects.requireNonNull(value).toString()); return this;
        }
        public Builder requestHeaders(HttpHeaders value) {
            HttpHeaders copy = new DefaultHttpHeaders();
            Objects.requireNonNull(value).forEach(e -> copy.add(e.getKey(), e.getValue())); headers = copy; return this;
        }
        public ExecuteContext build() { return new ExecuteContext(this); }
    }
}
