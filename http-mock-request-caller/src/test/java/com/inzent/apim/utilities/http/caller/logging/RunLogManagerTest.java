package com.inzent.apim.utilities.http.caller.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class RunLogManagerTest {
    @TempDir Path directory;
    @Test void disabledDoesNotCreateDirectories() {
        var path = directory.resolve("absent");
        try (var log = RunLogManager.open(path, false, 100, 2, true)) { log.log("control", "hello"); }
        assertFalse(Files.exists(path));
    }
    @Test void rollsPerStreamRetainsActiveAndReadableCompressedArchive() throws Exception {
        try (var log = RunLogManager.open(directory, true, 12, 3, true)) {
            for (int i=0;i<8;i++) log.log("worker-0001", "record-" + i);
            log.log("control", "separate");
            assertThrows(IllegalArgumentException.class, () -> log.log("../escape", "bad"));
        }
        assertTrue(Files.readString(directory.resolve("control.log")).contains("separate"));
        try (var files = Files.walk(directory)) {
            assertEquals(3, files.filter(p -> p.getFileName().toString().startsWith("worker-0001")).count());
        }
        try (var in = new GZIPInputStream(Files.newInputStream(directory.resolve("archive/worker-0001.1.log.gz")))) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("record-6"));
        }
    }
    @Test void maxFilesOneTruncatesRecordsAndCloseRejectsWrites() throws Exception {
        var log = RunLogManager.open(directory, true, 10, 1, false);
        log.log("control", "old");
        log.log("control", "가".repeat(20000));
        log.close();
        assertTrue(Files.size(directory.resolve("control.log")) <= 16384);
        assertFalse(Files.readString(directory.resolve("control.log")).contains("old"));
        assertThrows(IllegalStateException.class, () -> log.log("control", "closed"));
    }
}
