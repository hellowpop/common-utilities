package com.inzent.apim.utilities.http.payload;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayloadCancellationTest {
    @Test void interruptedCompressionStopsAndPreservesTheInterruptFlag() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var interruptPreserved = new java.util.concurrent.atomic.AtomicBoolean();
        var worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                new DefaultHttpPayloadGenerator().generate(false, ContentType.TEXT_PLAIN, ContentEncoding.GZIP,
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
    }
}
