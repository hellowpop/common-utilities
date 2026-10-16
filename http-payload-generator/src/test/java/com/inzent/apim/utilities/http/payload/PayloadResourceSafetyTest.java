package com.inzent.apim.utilities.http.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PayloadResourceSafetyTest {
    private enum Consumption { UNREAD, PARTIAL, FULL, CLOSE_EARLY, RUNTIME_FAILURE, NPE, ERROR }

    @ParameterizedTest @EnumSource(ContentEncoding.class)
    void everyCallbackExitClosesStreamAndDeletesTemporaryPayload(ContentEncoding encoding) throws IOException {
        Path directory = temporaryDirectory();
        var generator = new DefaultHttpPayloadGenerator();
        for (Consumption consumption : Consumption.values()) {
            Set<Path> before = payloads(directory);
            Set<Path> ownedFiles = new HashSet<>();
            AtomicReference<InputStream> stream = new AtomicReference<>();
            Throwable expected = switch (consumption) {
                case RUNTIME_FAILURE -> new IllegalStateException("callback failed");
                case NPE -> new NullPointerException("callback NPE");
                case ERROR -> new AssertionError("callback error");
                default -> null;
            };
            Runnable generate = () -> generator.generate(false, ContentType.APPLICATION_JSON, encoding, 20000, 17, (headers, in) -> {
                stream.set(in);
                try {
                    ownedFiles.addAll(payloads(directory));
                    ownedFiles.removeAll(before);
                    assertEquals(encoding == ContentEncoding.IDENTITY ? 0 : 1, ownedFiles.size());
                    switch (consumption) {
                        case PARTIAL -> assertTrue(in.read() >= 0);
                        case FULL -> in.transferTo(java.io.OutputStream.nullOutputStream());
                        case CLOSE_EARLY -> in.close();
                        default -> { }
                    }
                } catch (IOException e) { throw new UncheckedIOException(e); }
                if (expected instanceof RuntimeException failure) throw failure;
                if (expected instanceof Error failure) throw failure;
            });
            if (expected == null) generate.run();
            else assertSame(expected, assertThrows(expected.getClass(), generate::run));
            assertNotNull(stream.get());
            assertThrows(IOException.class, () -> stream.get().read());
            for (Path path : ownedFiles) assertFalse(Files.exists(path), "temporary payload remains: " + path);
        }
    }

    @Test void nullArgumentsFailFastWithParameterNamesAndNoTemporaryFile() throws IOException {
        Path directory = temporaryDirectory();
        Set<Path> before = payloads(directory);
        var generator = new DefaultHttpPayloadGenerator();
        for (ContentEncoding encoding : ContentEncoding.values()) {
            assertEquals("contentType", assertThrows(NullPointerException.class,
                () -> generator.generate(false, null, encoding, 100, 7, (h, in) -> fail())).getMessage());
        }
        assertEquals("contentEncoding", assertThrows(NullPointerException.class,
            () -> generator.generate(false, ContentType.TEXT_PLAIN, null, 100, 7, (h, in) -> fail())).getMessage());
        for (ContentEncoding encoding : ContentEncoding.values()) {
            assertEquals("acceptor", assertThrows(NullPointerException.class,
                () -> generator.generate(false, ContentType.TEXT_PLAIN, encoding, 100, 7, null)).getMessage());
        }
        for (ContentType type : ContentType.values()) {
            assertEquals("acceptor", assertThrows(NullPointerException.class,
                () -> type.generate(100, 7, null)).getMessage());
        }
        assertEquals(before, payloads(directory));
    }

    @Test void invalidReadArgumentsDoNotAdvanceOrBreakStream() {
        ContentType.TEXT_PLAIN.generate(100, 7, in -> {
            assertThrows(NullPointerException.class, () -> in.read(null, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[1], -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[1], 0, 2));
            try { assertEquals('k', in.read()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private static Path temporaryDirectory() throws IOException {
        Path probe = Files.createTempFile("payload-safety-test-", ".tmp");
        try { return probe.getParent(); }
        finally { Files.delete(probe); }
    }

    private static Set<Path> payloads(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("http-payload-"))
                .filter(path -> path.getFileName().toString().endsWith(".bin"))
                .collect(Collectors.toSet());
        }
    }
}
