package com.inzent.apim.utilities.netty.http.mockserver.response;
import io.netty.handler.codec.http.FullHttpResponse;
import java.util.Objects;
public sealed interface ActionResult {
    record SendResponse(FullHttpResponse response) implements ActionResult {
        public SendResponse { Objects.requireNonNull(response); }
    }
    record FakeCloseResponse(FullHttpResponse response) implements ActionResult {
        public FakeCloseResponse { Objects.requireNonNull(response); }
    }
    record CloseConnection() implements ActionResult {}
    record DelayCloseConnection(long delayMillis) implements ActionResult {
        public DelayCloseConnection {
            if (delayMillis < 0 || delayMillis > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid delay");
        }
    }
}
