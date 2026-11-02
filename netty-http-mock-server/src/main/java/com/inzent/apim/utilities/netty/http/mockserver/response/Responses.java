package com.inzent.apim.utilities.netty.http.mockserver.response;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import java.nio.charset.StandardCharsets;
public final class Responses {
    private Responses() {}
    public static FullHttpResponse text(int status, String body) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        return response;
    }
}
