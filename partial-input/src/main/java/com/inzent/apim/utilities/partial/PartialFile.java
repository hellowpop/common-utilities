package com.inzent.apim.utilities.partial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * File blocks concatenated in list order. Packing and range rules match {@link PartialByteArray}.
 * The path is resolved to its real target at construction. No handle is retained by the constructor.
 * The source must not be externally modified while this view is in use. This class is not thread-safe.
 */
public final class PartialFile {
    private final Path source;
    private PartialBlocks selection;

    public PartialFile(String source, List<Long> partialInfo) throws IOException {
        this(Path.of(Objects.requireNonNull(source, "source")), partialInfo);
    }

    /** @throws IllegalArgumentException if a block is negative or outside the source */
    public PartialFile(Path source, List<Long> partialInfo) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(partialInfo, "partialInfo");
        this.source = source.toRealPath();
        if (!Files.isRegularFile(this.source)) throw new IOException("Not a regular file: " + this.source);
        try (var channel = FileChannel.open(this.source, StandardOpenOption.READ)) {
            selection = new PartialBlocks(channel.size(), partialInfo);
        }
    }

    /**
     * Returns a new independent stream which owns its file handle; callers must close it.
     * No whole-file buffering is performed. Mark/reset is not supported.
     */
    public InputStream openStream() throws IOException {
        FileChannel channel = FileChannel.open(source, StandardOpenOption.READ);
        try {
            return new PartialInputStream(null, channel, selection);
        } catch (RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Replaces the original path with the concatenated selection, using a sibling temporary file
     * and an atomic move. Close all streams before calling. On success this view selects the entire
     * compacted file, so subsequent shrink calls retain the same contents.
     * Requires temporary disk space equal to the selected length and atomic replacement support.
     * The replacement has the temporary file's metadata; hard links retain the old file contents.
     * @return the resolved source path
     * @throws IOException on copy or replacement failure (no non-atomic fallback is attempted)
     */
    public Path shrink() throws IOException {
        PartialBlocks compacted = selection.compacted();
        Path temporary = Files.createTempFile(source.getParent(), ".partial-", ".tmp");
        try {
            try (var input = openStream(); var output = Files.newOutputStream(temporary)) {
                input.transferTo(output);
            }
            Files.move(temporary, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            selection = compacted;
            return source;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
