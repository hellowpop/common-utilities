package com.inzent.apim.utilities.http.curl;

import java.io.*;
import java.nio.file.*;
import java.util.Objects;

/** Read-only captured entity; owned by its ExecuteContext. Sizes are encoded entity bytes. */
public final class BodyStore implements Closeable {
    public static final int MEMORY_LIMIT = 1_048_576;
    private final Path directory;
    private final TrackedStreams readers = new TrackedStreams();
    private byte[] memory;
    private Path file;
    private boolean ownsFile = true;
    private boolean captured;
    private long size;

    BodyStore(Path directory) { this.directory = Objects.requireNonNull(directory,"directory"); }

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
        try {
            byte[] block = new byte[8192];
            for (int count; (count = source.read(block)) != -1;) {
                if (count == 0) continue;
                if (disk == null && size + count > MEMORY_LIMIT) {
                    file = Files.createTempFile(directory, "body-", ".bin");
                    disk = new BufferedOutputStream(Files.newOutputStream(file));
                    buffer.writeTo(disk); buffer = null;
                }
                if (disk == null) buffer.write(block, 0, count); else disk.write(block, 0, count);
                size = Math.addExact(size, count);
            }
        } finally {
            if (buffer != null) memory = buffer.toByteArray();
            if (disk != null) disk.close();
        }
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
        memory = null;
        if (ownsFile && file != null) {
            failure = Cleanup.attempt(failure, () -> Files.deleteIfExists(file));
        }
        Cleanup.rethrow(failure);
    }
}
