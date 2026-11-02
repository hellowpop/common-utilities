package com.inzent.apim.utilities.netty.http.mockserver.server;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.*;
import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import com.inzent.apim.utilities.netty.http.mockserver.processor.AnnotationProcessor;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class HandlerTest {
    @HttpResponseType("fail")
    public static class FailingProcessor extends AnnotationProcessor {
        public ActionResult process(Channel c,FullHttpRequest r) { throw new IllegalStateException("private details"); }
    }
    @HttpResponseType("null")
    public static class NullProcessor extends AnnotationProcessor {
        public ActionResult process(Channel c,FullHttpRequest r) { return null; }
    }
    private static ActionRegistry builtins() {
        return ActionRegistry.discover(List.of("com.inzent.apim.utilities.netty.http.mockserver.processor"));
    }
    private static FullHttpRequest request(String action) {
        var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/");
        request.headers().set("x-response-action",action); return request;
    }
    private static EmbeddedChannel channel(ConnectionState state,ActionRegistry registry) {
        var config=ConfigLoader.load(new String[]{});
        return new EmbeddedChannel(new RequestTimeoutHandler(state,100),new ActionDispatchHandler(config,registry,state));
    }
    @Test void delayedCloseCancelsDeadlineAndReleasesRequests() {
        ConnectionState state=new ConnectionState();
        var channel=channel(state,builtins());
        try {
            FullHttpRequest first=request("delay-close"); first.headers().set("x-delay-amount","1000");
            channel.writeInbound(first);
            assertEquals(0,first.refCnt()); assertNull(state.requestDeadline);
            FullHttpRequest next=request("ok"); channel.writeInbound(next);
            assertEquals(0,next.refCnt()); assertNull(channel.readOutbound());
            channel.advanceTimeBy(999,TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks();
            assertTrue(channel.isActive());
            channel.advanceTimeBy(1,TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks();
            assertFalse(channel.isActive()); assertNull(state.delayedClose);
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void disconnectCancelsScheduledClose() {
        ConnectionState state=new ConnectionState();
        var channel=channel(state,builtins());
        try {
            FullHttpRequest request=request("delay-close"); request.headers().set("x-delay-amount","60000");
            channel.writeInbound(request); var timer=state.delayedClose;
            channel.close(); assertTrue(timer.isCancelled()); assertNull(state.delayedClose);
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void processorFailureAndNullBecome500AndReleaseRequest() {
        for (String action : List.of("fail","null")) {
            ConnectionState state=new ConnectionState();
            var channel=channel(state,ActionRegistry.ofClasses(List.of(FailingProcessor.class,NullProcessor.class)));
            try {
                var request=request(action); channel.writeInbound(request); assertEquals(0,request.refCnt());
                FullHttpResponse response=channel.readOutbound();
                try {
                    assertEquals(500,response.status().code());
                    assertEquals("PROCESSOR_ERROR\n",response.content().toString(java.nio.charset.StandardCharsets.UTF_8));
                    assertFalse(channel.isActive());
                } finally { response.release(); }
            } finally { channel.finishAndReleaseAll(); }
        }
    }
    @Test void fakeCloseHasFullBodyAndNoRequestOwnershipLeak() {
        ConnectionState state=new ConnectionState();
        var channel=channel(state,builtins());
        try {
            var request=request("fake-close"); channel.writeInbound(request);
            assertEquals(0,request.refCnt()); FullHttpResponse response=channel.readOutbound();
            try {
                assertEquals("keep-alive",response.headers().get(HttpHeaderNames.CONNECTION));
                assertEquals(2,response.content().readableBytes()); assertFalse(channel.isActive());
            } finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void outboundFailureReleasesResponseAndClosesChannel() {
        ConnectionState state=new ConnectionState();
        var config=ConfigLoader.load(new String[]{});
        var channel=new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise) {
                ReferenceCountUtil.release(msg); promise.setFailure(new IOException("write failed"));
            }
        },new RequestTimeoutHandler(state,100),new ActionDispatchHandler(config,builtins(),state));
        try {
            var request=request("copy-and-send"); channel.writeInbound(request);
            assertEquals(0,request.refCnt()); assertFalse(channel.isActive());
            assertEquals(0,state.pendingResponses); assertNull(state.requestDeadline);
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void slowTransportCannotAccumulateMoreThanPendingResponseLimit() {
        ConnectionState state=new ConnectionState();
        var config=ConfigLoader.load(new String[]{"--server.max-pending-responses=1"});
        record Pending(Object message,ChannelPromise promise) {}
        java.util.List<Pending> queued=new java.util.ArrayList<>();
        var channel=new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise) {
                queued.add(new Pending(msg,promise));
            }
            @Override public void close(ChannelHandlerContext ctx,ChannelPromise promise) throws Exception {
                var discarded=java.util.List.copyOf(queued); queued.clear();
                for (Pending pending:discarded) {
                    ReferenceCountUtil.release(pending.message());
                    pending.promise().tryFailure(new java.nio.channels.ClosedChannelException());
                }
                super.close(ctx,promise);
            }
        },new RequestTimeoutHandler(state,100),new ActionDispatchHandler(config,builtins(),state));
        try {
            var first=request("ok"); channel.writeInbound(first);
            FullHttpResponse firstResponse=(FullHttpResponse)queued.get(0).message();
            assertTrue(channel.isActive());
            var second=request("copy-and-send"); channel.writeInbound(second);
            assertFalse(channel.isActive()); assertEquals(0,first.refCnt()); assertEquals(0,second.refCnt());
            assertEquals(0,firstResponse.refCnt()); assertEquals(0,state.pendingResponses);
            assertTrue(queued.isEmpty());
        } finally { channel.finishAndReleaseAll(); }
    }
}
