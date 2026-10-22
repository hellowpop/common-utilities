package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContractsTest {
    private final ServiceEvent event = new ServiceEvent(Instant.now(), 1, "TEST", null,
        ServiceEventType.SERVICE_START, null, null, null, 0, 0, null, null, false,
        null, Map.of());

    @Test void chainContinuesAfterEveryExceptionAndPreservesOrder() {
        List<Integer> calls = new ArrayList<>();
        ServiceStatusListener first = e -> { assertSame(event, e); calls.add(1); throw new IllegalStateException("first"); };
        ServiceStatusListener second = e -> { calls.add(2); throw new IllegalArgumentException("second"); };
        ServiceStatusListenerChain chain = ServiceStatusListenerChain.of(first, second).andThen(e -> calls.add(3));
        assertDoesNotThrow(() -> chain.onEvent(event));
        assertEquals(List.of(1, 2, 3), calls);
    }

    @Test void loggingFailureDoesNotSkipRemainingListeners() {
        List<Integer> calls = new ArrayList<>();
        AtomicInteger logs = new AtomicInteger();
        ServiceStatusListenerChain chain = new ServiceStatusListenerChain(
            List.of(e -> { calls.add(1); throw new IllegalStateException(); }, e -> calls.add(2)),
            (index, e, failure) -> { logs.incrementAndGet(); throw new IllegalStateException("logger"); });
        assertDoesNotThrow(() -> chain.onEvent(event));
        assertEquals(1, logs.get());
        assertEquals(List.of(1, 2), calls);
    }

    @Test void chainsCopyInputAndFlattenWithoutChangingOriginalOrRemovingDuplicates() {
        List<Integer> calls = new ArrayList<>();
        ServiceStatusListener a = e -> calls.add(1);
        ServiceStatusListener[] input = {a};
        var original = ServiceStatusListenerChain.of(input);
        input[0] = e -> calls.add(9);
        var expanded = original.andThen(ServiceStatusListenerChain.of(a, e -> calls.add(2)));
        original.onEvent(event);
        expanded.onEvent(event);
        assertEquals(List.of(1, 1, 1, 2), calls);
        assertDoesNotThrow(() -> ServiceStatusListenerChain.of().onEvent(event));
        assertThrows(NullPointerException.class, () -> ServiceStatusListenerChain.of((ServiceStatusListener[]) null));
        assertThrows(NullPointerException.class, () -> original.andThen(null));
    }

    @Test void optionsRejectInvalidBoundsAndDurationOverflow() {
        assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().readChunkSize(0).build());
        assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().receiveBufferSize(-1).build());
        assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().lingerSeconds(65536).build());
        assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().readInterval(Duration.ofNanos(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().responseTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().readInterval(Duration.ofSeconds(Long.MAX_VALUE)).build());
        assertThrows(IllegalArgumentException.class, () -> new ServerOptions(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new CloseClientException(Duration.ofMillis(-1)));
    }

    @Test void builderReuseDoesNotChangeBuiltOptions() {
        var builder = MockOptions.builder().readChunkSize(5);
        var first = builder.build();
        builder.readChunkSize(20);
        assertEquals(5, first.readChunkSize());
        assertEquals(20, builder.build().readChunkSize());
    }

    @Test void responseCopiesBytesAndUsesUtf8() {
        byte[] bytes = {1, 2};
        var response = new MockResponse(200, "application/octet-stream", bytes);
        bytes[0] = 9;
        response.body()[1] = 9;
        assertArrayEquals(new byte[]{1, 2}, response.body());
        assertArrayEquals(new byte[]{(byte)0xEA, (byte)0xB0, (byte)0x80},
            MockResponse.utf8(200, "text/plain; charset=UTF-8", "가").body());
    }

    @Test void responseRejectsHeaderInjectionAndInvalidBodyStatuses() {
        assertThrows(IllegalArgumentException.class, () -> new MockResponse(200, "text/plain\r\nX: y", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new MockResponse(200, "한글", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new MockResponse(199, "text/plain", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new MockResponse(204, "text/plain", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new MockResponse(205, "text/plain", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new MockResponse(304, "text/plain", new byte[]{1}));
    }
}
