package com.inzent.apim.utilities.netty.http.mockserver.processor;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;

/** Returns the requested final HTTP status without a response body. */
@HttpResponseType("status")
public final class ResponseStatusActionProcessor extends AnnotationProcessor {
    @Override public ActionResult process(Channel channel,FullHttpRequest request) {
        var values=request.headers().getAll("x-status");
        if (values.size()!=1) throw new RequestValidationException("INVALID_STATUS");
        String value=values.get(0).trim();
        if (!value.matches("[2-5][0-9]{2}")) throw new RequestValidationException("INVALID_STATUS");
        return new ActionResult.SendResponse(Responses.text(Integer.parseInt(value),""));
    }
}
