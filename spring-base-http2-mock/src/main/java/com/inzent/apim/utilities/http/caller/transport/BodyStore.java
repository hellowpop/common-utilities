package com.inzent.apim.utilities.http.caller.transport;

import java.io.*;
import java.nio.file.*;
import java.util.Objects;

/** A bounded, single-owner body. Close input streams before transferring or closing the store. */
public final class BodyStore implements AutoCloseable {
    private final Path directory;
    private final long threshold, maximum;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private OutputStream output = memory;
    private Path file;
    private long size;
    private boolean finished, closed;

    private BodyStore(Path directory, long threshold, long maximum) throws IOException {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath();
        if (threshold < 0 || threshold > Integer.MAX_VALUE - 8 || maximum < 0)
            throw new IllegalArgumentException("Invalid body limits");
        this.threshold = threshold; this.maximum = maximum;
        Files.createDirectories(this.directory);
    }
    public static BodyStore create(Path directory, long memoryThreshold, long maxBytes) throws IOException {
        return new BodyStore(directory, memoryThreshold, maxBytes);
    }
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        checkOpen();
        if (finished) throw new IOException("Body already finished");
        if (length > maximum - size) throw new IOException("BODY_LIMIT: maximum " + maximum + " bytes");
        if (file == null && size + length > threshold) {
            Path promoted = Files.createTempFile(directory, "caller-body-", ".bin");
            OutputStream next = null;
            try {
                next = Files.newOutputStream(promoted);
                memory.writeTo(next);
                output = next; file = promoted; memory = null;
            } catch (Throwable e) {
                if (next != null) try { next.close(); } catch (Throwable cleanup) { e.addSuppressed(cleanup); }
                try { Files.deleteIfExists(promoted); } catch (Throwable cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
        }
        output.write(bytes, offset, length); size += length;
    }
    public synchronized void finish() throws IOException {
        checkOpen(); if (!finished) { output.close(); finished = true; }
    }
    public synchronized InputStream openStream() throws IOException {
        checkReadable();
        return file == null ? new ByteArrayInputStream(memory.toByteArray()) : Files.newInputStream(file);
    }
    public synchronized long size() { return size; }
    public synchronized byte[] readBytes(int maxBytes) throws IOException {
        checkReadable();
        if (maxBytes < 0 || size > maxBytes) throw new IOException("Body exceeds read limit");
        try (InputStream in = openStream()) { return in.readAllBytes(); }
    }
    public synchronized Path moveTo(Path destination) throws IOException {
        checkReadable();
        Path target = destination.toAbsolutePath();
        if (file != null) {
            try { Files.move(file, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(file, target); }
            file = null;
        } else Files.write(target, memory.toByteArray(), StandardOpenOption.CREATE_NEW);
        closed = true; memory = null; output = OutputStream.nullOutputStream();
        return target;
    }
    private void checkOpen() throws IOException { if (closed) throw new IOException("Body closed or transferred"); }
    private void checkReadable() throws IOException { checkOpen(); if (!finished) throw new IOException("Body not finished"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        try { output.close(); } catch (IOException e) { failure = e; }
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
        closed = true; memory = null; file = null; output = OutputStream.nullOutputStream();
    }
}
