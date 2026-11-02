package com.inzent.apim.utilities.netty.http.mockserver.server;

import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpRequest;
import java.util.concurrent.TimeUnit;

/** Absolute request/idle deadline: incoming fragments never extend it. */
final class RequestTimeoutHandler extends ChannelInboundHandlerAdapter {
    private final ConnectionState state;
    private final int timeoutMs;
    RequestTimeoutHandler(ConnectionState state, int timeoutMs) { this.state=state; this.timeoutMs=timeoutMs; }
    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception { arm(ctx); super.channelActive(ctx); }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) state.cancelRequestDeadline();
        super.channelRead(ctx,msg);
        if (msg instanceof FullHttpRequest && !state.terminal) arm(ctx);
    }
    private void arm(ChannelHandlerContext ctx) {
        state.cancelRequestDeadline();
        state.requestDeadline=ctx.executor().schedule(() -> {
            state.requestDeadline=null; state.terminal=true; ctx.close();
        },timeoutMs,TimeUnit.MILLISECONDS);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        state.cancelAll(); super.channelInactive(ctx);
    }
}
