package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


class ContractsTest {
    private final ServiceEvent event = new ServiceEvent(Instant.now(), 1, "TEST", null,
        ServiceEventType.SERVICE_START, null, null, null, 0, 0, null, null, false,
        null, Map.of());

    @Test void chainContinuesAfterEveryExceptionAndPreservesOrder() {
        List<Integer> calls = new ArrayList<>();
        ServiceStatusListener first = e -> { Assertions.assertSame(event, e); calls.add(1); throw new IllegalStateException("first"); };
        ServiceStatusListener second = e -> { calls.add(2); throw new IllegalArgumentException("second"); };
        ServiceStatusListenerChain chain = ServiceStatusListenerChain.of(first, second).andThen(e -> calls.add(3));
        Assertions.assertDoesNotThrow(() -> chain.onEvent(event));
        Assertions.assertEquals(List.of(1, 2, 3), calls);
    }

    @Test void loggingFailureDoesNotSkipRemainingListeners() {
        List<Integer> calls = new ArrayList<>();
        AtomicInteger logs = new AtomicInteger();
        ServiceStatusListenerChain chain = new ServiceStatusListenerChain(
            List.of(e -> { calls.add(1); throw new IllegalStateException(); }, e -> calls.add(2)),
            (index, e, failure) -> { logs.incrementAndGet(); throw new IllegalStateException("logger"); });
        Assertions.assertDoesNotThrow(() -> chain.onEvent(event));
        Assertions.assertEquals(1, logs.get());
        Assertions.assertEquals(List.of(1, 2), calls);
    }

    @Test void chainsCopyInputAndFlattenWithoutChangingOriginalOrRemovingDuplicates() {
        List<Integer> calls = new ArrayList<>();
        ServiceStatusListener a = e -> calls.add(1);
        ServiceStatusListener[] input = {a};
        com.inzent.apim.utilities.http.mockserver.ServiceStatusListenerChain original = ServiceStatusListenerChain.of(input);
        input[0] = e -> calls.add(9);
        com.inzent.apim.utilities.http.mockserver.ServiceStatusListenerChain expanded = original.andThen(ServiceStatusListenerChain.of(a, e -> calls.add(2)));
        original.onEvent(event);
        expanded.onEvent(event);
        Assertions.assertEquals(List.of(1, 1, 1, 2), calls);
        Assertions.assertDoesNotThrow(() -> ServiceStatusListenerChain.of().onEvent(event));
        Assertions.assertThrows(NullPointerException.class, () -> ServiceStatusListenerChain.of((ServiceStatusListener[]) null));
        Assertions.assertThrows(NullPointerException.class, () -> original.andThen(null));
    }

    @Test void optionsRejectInvalidBoundsAndDurationOverflow() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().readChunkSize(0).build());
        Assertions.assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().receiveBufferSize(-1).build());
        Assertions.assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().lingerSeconds(65536).build());
        Assertions.assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().readInterval(Duration.ofNanos(-1)).build());
        Assertions.assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().responseTimeout(Duration.ZERO).build());
        Assertions.assertThrows(IllegalArgumentException.class, () -> MockOptions.builder().readInterval(Duration.ofSeconds(Long.MAX_VALUE)).build());
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ServerOptions(0, Duration.ofSeconds(1)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new CloseClientException(Duration.ofMillis(-1)));
    }

    @Test void builderReuseDoesNotChangeBuiltOptions() {
        com.inzent.apim.utilities.http.mockserver.MockOptions.Builder builder = MockOptions.builder().readChunkSize(5);
        com.inzent.apim.utilities.http.mockserver.MockOptions first = builder.build();
        builder.readChunkSize(20);
        Assertions.assertEquals(5, first.readChunkSize());
        Assertions.assertEquals(20, builder.build().readChunkSize());
    }

    @Test void responseCopiesBytesAndUsesUtf8() {
        byte[] bytes = {1, 2};
        com.inzent.apim.utilities.http.mockserver.MockResponse response = new MockResponse(200, "application/octet-stream", bytes);
        bytes[0] = 9;
        response.body()[1] = 9;
        Assertions.assertArrayEquals(new byte[]{1, 2}, response.body());
        Assertions.assertArrayEquals(new byte[]{(byte)0xEA, (byte)0xB0, (byte)0x80},
            MockResponse.utf8(200, "text/plain; charset=UTF-8", "가").body());
    }

    @Test void responseRejectsHeaderInjectionAndInvalidBodyStatuses() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new MockResponse(200, "text/plain\r\nX: y", new byte[0]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new MockResponse(200, "한글", new byte[0]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new MockResponse(199, "text/plain", new byte[0]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new MockResponse(204, "text/plain", new byte[]{1}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new MockResponse(205, "text/plain", new byte[]{1}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new MockResponse(304, "text/plain", new byte[]{1}));
    }
}
