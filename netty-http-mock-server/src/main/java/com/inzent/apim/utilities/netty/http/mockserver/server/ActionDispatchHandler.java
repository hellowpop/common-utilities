package com.inzent.apim.utilities.netty.http.mockserver.server;

import com.inzent.apim.utilities.netty.http.mockserver.config.ServerConfig;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ActionDispatchHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final System.Logger LOG=System.getLogger(ActionDispatchHandler.class.getName());
    private final ServerConfig config;
    private final ActionRegistry registry;
    private final ConnectionState state;
    ActionDispatchHandler(ServerConfig config, ActionRegistry registry, ConnectionState state) {
        this.config=config; this.registry=registry; this.state=state;
    }
    @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (state.terminal) return;
        if (!request.decoderResult().isSuccess()) { error(ctx,request.method(),400,"BAD_REQUEST"); return; }
        if (!request.protocolVersion().equals(HttpVersion.HTTP_1_1)) {
            error(ctx,request.method(),505,"HTTP_VERSION_NOT_SUPPORTED"); return;
        }
        List<String> values=request.headers().getAll(config.headerName());
        if (values.size()>1) { error(ctx,request.method(),400,"BAD_REQUEST"); return; }
        String name=values.isEmpty() ? config.defaultAction() : values.get(0).trim();
        if (!ActionRegistry.validName(name)) { error(ctx,request.method(),400,"BAD_REQUEST"); return; }
        if (!registry.names().contains(name)) { error(ctx,request.method(),400,"UNKNOWN_ACTION"); return; }
        try {
            ActionResult result=registry.execute(name,ctx.channel(),request);
            if (result instanceof ActionResult.SendResponse send) {
                send(ctx,request.method(),send.response(),HttpUtil.isKeepAlive(request),false,!HttpUtil.isKeepAlive(request));
            } else if (result instanceof ActionResult.FakeCloseResponse fake) {
                send(ctx,request.method(),fake.response(),true,true,true);
            } else if (result instanceof ActionResult.CloseConnection) {
                terminal(ctx);
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } else if (result instanceof ActionResult.DelayCloseConnection delay) {
                terminal(ctx);
                // Continue consuming/discarding bytes so a peer FIN can release this connection's admission.
                ctx.channel().config().setAutoRead(true);
                ctx.flush();
                state.delayedClose=ctx.channel().eventLoop().schedule(() -> {
                    state.delayedClose=null; ctx.close();
                },delay.delayMillis(),TimeUnit.MILLISECONDS);
            } else throw new IllegalStateException("Processor returned no result");
        } catch (RequestValidationException ex) {
            error(ctx,request.method(),400,ex.getMessage());
        } catch (Exception ex) {
            LOG.log(System.Logger.Level.WARNING,"Action processing failed: " + ex.getClass().getSimpleName());
            if (state.terminal) ctx.close();
            else error(ctx,request.method(),500,"PROCESSOR_ERROR");
        }
    }
    private void send(ChannelHandlerContext ctx, HttpMethod method, FullHttpResponse response,
            boolean keepAlive, boolean fake, boolean close) {
        if (state.pendingResponses>=config.maxPendingResponses()) {
            ReferenceCountUtil.release(response); terminal(ctx); ctx.close(); return;
        }
        if (close) terminal(ctx);
        state.pendingResponses++;
        try {
            ResponseWriter.write(ctx,method,response,keepAlive,fake,close).addListener(future -> state.pendingResponses--);
        } catch (RuntimeException ex) { state.pendingResponses--; throw ex; }
    }
    private void error(ChannelHandlerContext ctx, HttpMethod method, int status, String code) {
        try { send(ctx,method,Responses.text(status,code+"\n"),false,false,true); }
        catch (RuntimeException ex) { ctx.close(); }
    }
    private void terminal(ChannelHandlerContext ctx) {
        state.terminal=true; state.cancelRequestDeadline(); ctx.channel().config().setAutoRead(false);
    }
    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (!state.terminal) ctx.channel().config().setAutoRead(ctx.channel().isWritable());
        super.channelWritabilityChanged(ctx);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!state.terminal && ctx.channel().isActive()) error(ctx,HttpMethod.GET,400,"BAD_REQUEST");
        else ctx.close();
    }
}
