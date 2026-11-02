package com.inzent.apim.utilities.netty.http.mockserver.processor;
import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
@HttpResponseType("copy-and-send")
public final class ResponseCopyAndSendActionProcessor extends AnnotationProcessor {
    @Override public ActionResult process(Channel channel, FullHttpRequest request) {
        var content = request.content().copy();
        try {
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
            String type = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, type == null ? "application/octet-stream" : type);
            return new ActionResult.SendResponse(response);
        } catch (Throwable failure) { content.release(); throw failure; }
    }
}
