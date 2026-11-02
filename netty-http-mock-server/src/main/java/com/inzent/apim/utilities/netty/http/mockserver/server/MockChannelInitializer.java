package com.inzent.apim.utilities.netty.http.mockserver.server;

import com.inzent.apim.utilities.netty.http.mockserver.config.ServerConfig;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.atomic.AtomicInteger;

final class MockChannelInitializer extends ChannelInitializer<Channel> {
    private final ServerConfig config;
    private final ActionRegistry registry;
    private final AtomicInteger connections;
    private final ChannelGroup children;
    MockChannelInitializer(ServerConfig config, ActionRegistry registry, AtomicInteger connections, ChannelGroup children) {
        this.config=config; this.registry=registry; this.connections=connections; this.children=children;
    }
    @Override protected void initChannel(Channel channel) {
        ConnectionState state=new ConnectionState();
        channel.pipeline().addLast("admission",new ChannelInboundHandlerAdapter() {
            private boolean admitted;
            @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
                if (connections.incrementAndGet()>config.maxConnections()) {
                    connections.decrementAndGet(); state.terminal=true; ctx.close(); return;
                }
                admitted=true; children.add(ctx.channel()); super.channelActive(ctx);
            }
            @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                if (admitted) { admitted=false; connections.decrementAndGet(); }
                state.cancelAll(); super.channelInactive(ctx);
            }
        });
        channel.pipeline().addLast("terminal-byte-guard",new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx,Object msg) throws Exception {
                if (state.terminal) ReferenceCountUtil.release(msg); else super.channelRead(ctx,msg);
            }
        });
        channel.pipeline().addLast("codec",new HttpServerCodec(config.maxInitialLineLength(),config.maxHeaderSize(),8192));
        // Blocks decoded trailing messages before the aggregator can send protocol responses after terminal selection.
        channel.pipeline().addLast("terminal-guard",new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx,Object msg) throws Exception {
                if (state.terminal) ReferenceCountUtil.release(msg); else super.channelRead(ctx,msg);
            }
        });
        channel.pipeline().addLast("aggregator",new HttpObjectAggregator(config.maxContentLength(),true) {
            @Override protected void handleOversizedMessage(ChannelHandlerContext ctx,HttpMessage message) throws Exception {
                state.terminal=true; state.cancelRequestDeadline(); ctx.channel().config().setAutoRead(false);
                super.handleOversizedMessage(ctx,message);
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
        channel.pipeline().addLast("request-timeout",new RequestTimeoutHandler(state,config.requestTimeoutMs()));
        channel.pipeline().addLast("dispatch",new ActionDispatchHandler(config,registry,state));
    }
}
