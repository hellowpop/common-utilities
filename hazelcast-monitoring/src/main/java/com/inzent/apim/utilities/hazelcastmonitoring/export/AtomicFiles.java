package com.inzent.apim.utilities.hazelcastmonitoring.export;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Publishes completed files without clobbering another process's output. */
public final class AtomicFiles {
    private AtomicFiles() {}
    @FunctionalInterface public interface Content { void write(Writer writer) throws IOException; }
    public static void write(Path path, Content content) throws IOException {
        Path target=path.toAbsolutePath().normalize(); Files.createDirectories(target.getParent());
        if(Files.exists(target))throw new FileAlreadyExistsException(target.toString());
        Path temp=Files.createTempFile(target.getParent(),".hzmon-",".tmp");
        try {
            try(var writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)) { content.write(writer); }
            try { Files.createLink(target,temp); }
            catch(FileAlreadyExistsException e) { throw e; }
            catch(UnsupportedOperationException|IOException e) {
                // No ATOMIC_MOVE: some providers replace an existing target with that option.
                Files.move(temp,target);
            }
        } finally { Files.deleteIfExists(temp); }
    }
}
