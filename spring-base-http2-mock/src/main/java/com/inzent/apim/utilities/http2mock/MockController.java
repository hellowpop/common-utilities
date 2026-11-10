package com.inzent.apim.utilities.http2mock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.*;

@RestController
public class MockController {
    private final TimeStreams streams;
    public MockController(TimeStreams streams) { this.streams = streams; }

    @RequestMapping(value = "/api/echo", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public EchoResponse echo(HttpServletRequest request) {
        Map<String, List<String>> headers = new TreeMap<>();
        Collections.list(request.getHeaderNames()).forEach(name ->
                headers.put(name.toLowerCase(Locale.ROOT), Collections.list(request.getHeaders(name))));
        return new EchoResponse(request.getMethod(), request.getRequestURI(), request.getQueryString(), request.getProtocol(), headers);
    }

    public record EchoResponse(String method, String uri, String query, String protocol, Map<String, List<String>> headers) { }

    @GetMapping("/http2/ping")
    public Map<String, Object> ping(HttpServletRequest request) {
        return Map.of("ack", true, "protocol", request.getProtocol(), "serverTime", Instant.now());
    }

    @GetMapping(value = "/sse/call", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(15_000L);
        var subscription = streams.open(event -> emitter.send(SseEmitter.event()
                        .id(Integer.toString(event.sequence())).name("time").data(event)),
                emitter::complete, emitter::completeWithError);
        emitter.onCompletion(subscription::cancel);
        emitter.onTimeout(subscription::cancel);
        emitter.onError(error -> subscription.cancel());
        return emitter;
    }
}
