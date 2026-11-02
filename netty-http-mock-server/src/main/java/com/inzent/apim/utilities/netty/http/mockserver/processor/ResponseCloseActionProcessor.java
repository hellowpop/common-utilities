package com.inzent.apim.utilities.netty.http.mockserver.processor;
import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
@HttpResponseType("close")
public final class ResponseCloseActionProcessor extends AnnotationProcessor {
    @Override public ActionResult process(Channel channel, FullHttpRequest request) {
        return new ActionResult.CloseConnection();
    }
}
