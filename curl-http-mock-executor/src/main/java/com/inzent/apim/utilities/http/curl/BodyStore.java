package com.inzent.apim.utilities.http.curl;

import java.io.*;
import java.nio.file.*;
import java.util.Objects;

/** Read-only captured entity; owned by its ExecuteContext. Sizes are encoded entity bytes. */
public final class BodyStore implements Closeable {
    public static final int MEMORY_LIMIT = 1_048_576;
    private final Path directory;
    private final BodyFileFactory files;
    private final TrackedStreams readers = new TrackedStreams();
    private byte[] memory;
    private Path file;
    private OutputStream pendingWriter;
    private boolean ownsFile = true;
    private boolean captured;
    private long size;

    BodyStore(Path directory) { this(directory, path -> new BufferedOutputStream(Files.newOutputStream(path))); }
    BodyStore(Path directory, BodyFileFactory files) {
        this.directory = Objects.requireNonNull(directory,"directory");
        this.files = Objects.requireNonNull(files,"files");
    }

    static BodyStore reference(Path file) throws IOException {
        BodyStore body = new BodyStore(file.getParent());
        body.file = file; body.ownsFile = false; body.size = Files.size(file); body.captured = true;
        return body;
    }

    synchronized void copyFrom(InputStream source) throws IOException {
        Objects.requireNonNull(source,"source");
        readers.checkOpen();
        if (captured) throw new IllegalStateException("Body already captured");
        captured = true;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OutputStream disk = null;
        Throwable failure = null;
        try {
            byte[] block = new byte[8192];
            for (int count; (count = source.read(block)) != -1;) {
                if (count == 0) continue;
                if (disk == null && size + count > MEMORY_LIMIT) {
                    file = Files.createTempFile(directory, "body-", ".bin");
                    disk = files.open(file);
                    pendingWriter = disk;
                    buffer.writeTo(disk); buffer = null;
                }
                if (disk == null) buffer.write(block, 0, count); else disk.write(block, 0, count);
                size = Math.addExact(size, count);
            }
        } catch (Throwable error) { failure = error; }
        finally {
            if (buffer != null) {
                try { memory = buffer.toByteArray(); }
                catch (Throwable error) { failure = Cleanup.merge(failure,error); }
            }
            if (disk != null) {
                try { disk.close(); pendingWriter = null; }
                catch (Throwable error) { failure = Cleanup.merge(failure,error); }
            }
        }
        Cleanup.rethrow(failure);
    }

    public long size() { return size; }
    public boolean isFileBacked() { return file != null; }
    public synchronized InputStream openStream() throws IOException {
        readers.checkOpen();
        if (!captured) throw new IOException("Body is not captured");
        return readers.track(file == null ? new ByteArrayInputStream(memory) : Files.newInputStream(file));
    }

    @Override public synchronized void close() throws IOException {
        Throwable failure = Cleanup.attempt(null,readers);
        if (pendingWriter != null) {
            try { pendingWriter.close(); pendingWriter = null; }
            catch (Throwable error) { failure = Cleanup.merge(failure,error); }
        }
        memory = null;
        if (ownsFile && file != null) {
            failure = Cleanup.attempt(failure, () -> Files.deleteIfExists(file));
        }
        Cleanup.rethrow(failure);
    }
}
