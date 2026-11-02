package com.inzent.apim.utilities.netty.http.mockserver.processor;
import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
@HttpResponseType("error-500")
public final class ResponseErrorActionProcessor extends AnnotationProcessor {
    @Override public ActionResult process(Channel channel, FullHttpRequest request) {
        return new ActionResult.SendResponse(Responses.text(500, "MOCK_ERROR\n"));
    }
}
