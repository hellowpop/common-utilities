package com.inzent.apim.utilities.netty.http.mockserver.response;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;

/** Sole owner of HTTP framing and outbound response handoff. */
public final class ResponseWriter {
    private ResponseWriter() {}
    public static ChannelFuture write(ChannelHandlerContext ctx, HttpMethod method, FullHttpResponse response,
            boolean keepAlive, boolean fakeClose, boolean closeAfterWrite) {
        boolean handedOff=false;
        try {
            int status=response.status().code();
            int length=response.content().readableBytes();
            response.setProtocolVersion(HttpVersion.HTTP_1_1);
            response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            if (status==204 || status==304 || status<200) {
                response.content().clear(); response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
            } else {
                HttpUtil.setContentLength(response,length);
                if (HttpMethod.HEAD.equals(method)) response.content().clear();
            }
            if (fakeClose) response.headers().set(HttpHeaderNames.CONNECTION,HttpHeaderValues.KEEP_ALIVE);
            else HttpUtil.setKeepAlive(response,keepAlive && !closeAfterWrite);
            if (!ctx.channel().isActive()) throw new IllegalStateException("Inactive channel");
            ChannelFuture future=ctx.writeAndFlush(response);
            handedOff=true;
            if (closeAfterWrite) future.addListener(ChannelFutureListener.CLOSE);
            else future.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            return future;
        } finally {
            if (!handedOff) ReferenceCountUtil.safeRelease(response);
        }
    }
}
