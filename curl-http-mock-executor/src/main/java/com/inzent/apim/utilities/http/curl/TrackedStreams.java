package com.inzent.apim.utilities.http.curl;

import java.io.*;
import java.util.*;

/** Tracks readers so context disposal also invalidates readers retained by a caller. */
final class TrackedStreams implements Closeable {
    private final Set<InputStream> readers = new HashSet<>();
    private boolean closed;

    synchronized void checkOpen() throws IOException {
        if (closed) throw new IOException("Resource is closed");
    }

    synchronized InputStream track(InputStream source) throws IOException {
        Objects.requireNonNull(source,"source");
        if (closed) {
            Throwable failure = Cleanup.attempt(new IOException("Resource is closed"),source);
            Cleanup.rethrow(failure);
        }
        InputStream wrapper = new FilterInputStream(source) {
            private boolean disposed;
            private void check() throws IOException { if (disposed || closed) throw new IOException("Stream is closed"); }
            @Override public int read() throws IOException { synchronized (TrackedStreams.this) { check(); return in.read(); } }
            @Override public int read(byte[] b, int off, int len) throws IOException { synchronized (TrackedStreams.this) { check(); return in.read(b, off, len); } }
            @Override public long skip(long n) throws IOException { synchronized (TrackedStreams.this) { check(); return in.skip(n); } }
            @Override public int available() throws IOException { synchronized (TrackedStreams.this) { check(); return in.available(); } }
            @Override public void reset() throws IOException { synchronized (TrackedStreams.this) { check(); in.reset(); } }
            @Override public void mark(int limit) { synchronized (TrackedStreams.this) { if (!disposed && !closed) in.mark(limit); } }
            @Override public boolean markSupported() { synchronized (TrackedStreams.this) { return !disposed && !closed && in.markSupported(); } }
            @Override public void close() throws IOException {
                synchronized (TrackedStreams.this) {
                    if (!disposed) {
                        in.close(); in = null; disposed = true; readers.remove(this);
                    }
                }
            }
        };
        readers.add(wrapper);
        return wrapper;
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        Throwable failure = null;
        for (InputStream reader : List.copyOf(readers)) {
            failure = Cleanup.attempt(failure,reader);
        }
        Cleanup.rethrow(failure);
    }
}
