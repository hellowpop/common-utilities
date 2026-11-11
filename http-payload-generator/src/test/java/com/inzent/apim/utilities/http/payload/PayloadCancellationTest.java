package com.inzent.apim.utilities.http.payload;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class PayloadCancellationTest {
    @ParameterizedTest @EnumSource(value = ContentEncoding.class, names = "IDENTITY", mode = EnumSource.Mode.EXCLUDE)
    void interruptedCompressionStopsAndPreservesTheInterruptFlag(ContentEncoding encoding) throws Exception {
        Path probe = Files.createTempFile("payload-cancel-probe-", ".tmp");
        Path directory = probe.getParent();
        Files.delete(probe);
        Set<Path> before = payloads(directory);
        var failure = new AtomicReference<Throwable>();
        var interruptPreserved = new java.util.concurrent.atomic.AtomicBoolean();
        var worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                new DefaultHttpPayloadGenerator().generate(false, ContentType.MULTIPART_FORM_DATA, encoding,
                        1_000_000, 128, (headers, body) -> fail("cancelled compression must not invoke the callback"));
                failure.set(new AssertionError("compression did not cancel"));
            } catch (Throwable error) { failure.set(error); }
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });
        worker.start(); worker.join(3000);
        assertFalse(worker.isAlive());
        assertInstanceOf(UncheckedIOException.class, failure.get());
        assertInstanceOf(InterruptedIOException.class, failure.get().getCause());
        assertTrue(interruptPreserved.get());
        assertEquals(before, payloads(directory), "cancelled compression left a temporary payload");
    }

    private static Set<Path> payloads(Path directory) throws java.io.IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("http-payload-"))
                .filter(path -> path.getFileName().toString().endsWith(".bin"))
                .collect(Collectors.toSet());
        }
    }
}
