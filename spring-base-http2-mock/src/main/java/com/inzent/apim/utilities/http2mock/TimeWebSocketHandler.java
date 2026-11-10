package com.inzent.apim.utilities.http2mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class TimeWebSocketHandler extends TextWebSocketHandler {
    private final TimeStreams streams;
    private final ObjectMapper json;
    private final ConcurrentMap<String, TimeStreams.Subscription> subscriptions = new ConcurrentHashMap<>();

    public TimeWebSocketHandler(TimeStreams streams, ObjectMapper json) { this.streams = streams; this.json = json; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var subscription = streams.open(event -> session.sendMessage(new TextMessage(json.writeValueAsString(event))),
                () -> close(session, CloseStatus.NORMAL), error -> close(session, CloseStatus.SERVER_ERROR));
        subscriptions.put(session.getId(), subscription);
        if (!session.isOpen()) cancel(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { cancel(session); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) { close(session, CloseStatus.SERVER_ERROR); }

    private void cancel(WebSocketSession session) {
        var subscription = subscriptions.remove(session.getId());
        if (subscription != null) subscription.cancel();
    }
    private void close(WebSocketSession session, CloseStatus status) {
        cancel(session);
        try { if (session.isOpen()) session.close(status); }
        catch (IOException ignored) { /* The transport is already unusable; the timer is cancelled. */ }
    }
}
