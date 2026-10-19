package com.inzent.apim.utilities.http.curl;

import java.io.*;
import java.nio.file.*;

/** Unmodified stdout or stderr file, valid until context disposal. */
public final class ProcessOutputRecord implements Closeable {
    private final Path path;
    private final TrackedStreams readers = new TrackedStreams();
    private long size;
    ProcessOutputRecord(Path path) { this.path = path; }
    void finish() throws IOException { size = Files.size(path); }
    public Path path() { return path; }
    public long size() { return size; }
    public synchronized InputStream openStream() throws IOException { readers.checkOpen(); return readers.track(Files.newInputStream(path)); }
    @Override public synchronized void close() throws IOException {
        Throwable failure = Cleanup.attempt(null,readers);
        failure = Cleanup.attempt(failure, () -> Files.deleteIfExists(path));
        Cleanup.rethrow(failure);
    }
}
