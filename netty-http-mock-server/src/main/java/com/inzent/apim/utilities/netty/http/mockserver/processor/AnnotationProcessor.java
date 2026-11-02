package com.inzent.apim.utilities.netty.http.mockserver.processor;
import com.inzent.apim.utilities.netty.http.mockserver.response.ActionResult;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
/** Shared instances must be stateless; processors never write, close or release the request. */
public abstract class AnnotationProcessor {
    public abstract ActionResult process(Channel channel, FullHttpRequest request);
}
