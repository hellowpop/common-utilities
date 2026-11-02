package com.inzent.apim.utilities.netty.http.mockserver.processor;
import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
@HttpResponseType("fake-close")
public final class ResponseFakeCloseActionProcessor extends AnnotationProcessor {
    @Override public ActionResult process(Channel channel, FullHttpRequest request) {
        return new ActionResult.FakeCloseResponse(Responses.text(200, "OK"));
    }
}
