package com.inzent.apim.utilities.netty.http.mockserver.processor;
import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
@HttpResponseType("delay-close")
public final class ResponseDelayCloseActionProcessor extends AnnotationProcessor {
    @Override public ActionResult process(Channel channel, FullHttpRequest request) {
        var values = request.headers().getAll("x-delay-amount");
        if (values.size() != 1) throw new RequestValidationException("INVALID_DELAY_AMOUNT");
        String value = values.get(0).trim();
        if (!value.matches("[0-9]+")) throw new RequestValidationException("INVALID_DELAY_AMOUNT");
        try {
            long delay = Long.parseLong(value);
            if (delay > Integer.MAX_VALUE) throw new NumberFormatException();
            return new ActionResult.DelayCloseConnection(delay);
        } catch (NumberFormatException ex) { throw new RequestValidationException("INVALID_DELAY_AMOUNT"); }
    }
}
