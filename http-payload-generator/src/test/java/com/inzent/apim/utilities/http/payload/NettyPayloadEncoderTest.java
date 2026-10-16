package com.inzent.apim.utilities.http.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NettyPayloadEncoderTest {
    @Test void originalReadFailureSurvivesCleanupFailure() {
        IOException readFailure = new IOException("input failed");
        IllegalStateException closeFailure = new IllegalStateException("channel close failed");
        boolean[] removed = {false};
        var handler = new ChannelOutboundHandlerAdapter() {
            @Override public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                ctx.fireExceptionCaught(closeFailure);
                ctx.close(promise);
            }
            @Override public void handlerRemoved(ChannelHandlerContext ctx) { removed[0] = true; }
        };
        InputStream source = new InputStream() {
            @Override public int read() throws IOException { throw readFailure; }
        };
        IOException actual = assertThrows(IOException.class,
            () -> NettyPayloadEncoder.encode(handler, source, new ByteArrayOutputStream()));
        assertSame(readFailure, actual);
        assertArrayEquals(new Throwable[] {closeFailure}, actual.getSuppressed());
        assertTrue(removed[0]);
    }

    @Test void failedWriteReleasesCurrentAndQueuedBuffers() {
        TrackingEncoder encoder = new TrackingEncoder();
        IOException failure = new IOException("disk full");
        OutputStream target = new OutputStream() {
            @Override public void write(int value) throws IOException { throw failure; }
        };
        assertSame(failure, assertThrows(IOException.class, () ->
            NettyPayloadEncoder.encode(encoder, new ByteArrayInputStream(new byte[10]), target)));
        assertEquals(2, encoder.buffers.size());
        encoder.buffers.forEach(buffer -> assertEquals(0, buffer.refCnt()));
        assertTrue(encoder.removed);
    }

    @Test void failureAfterOneBlockReleasesCodecAndLeavesStreamsWithCaller() {
        TrackingEncoder encoder = new TrackingEncoder();
        IOException failure = new IOException("read failed after first block");
        boolean[] sourceClosed = {false};
        boolean[] targetClosed = {false};
        InputStream source = new InputStream() {
            private int count;
            @Override public int read() { throw new AssertionError("bulk read expected"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (count++ != 0) throw failure;
                bytes[offset] = 1;
                return 1;
            }
            @Override public void close() { sourceClosed[0] = true; }
        };
        OutputStream target = new ByteArrayOutputStream() {
            @Override public void close() { targetClosed[0] = true; }
        };
        assertSame(failure, assertThrows(IOException.class,
            () -> NettyPayloadEncoder.encode(encoder, source, target)));
        encoder.buffers.forEach(buffer -> assertEquals(0, buffer.refCnt()));
        assertTrue(encoder.removed);
        assertFalse(sourceClosed[0]);
        assertFalse(targetClosed[0]);
    }

    @Test void handlerInitializationFailureClosesChannelAndReleasesItsBuffers() {
        IllegalStateException failure = new IllegalStateException("handler initialization failed");
        ByteBuf[] allocated = {null};
        boolean[] channelClosed = {false};
        var handler = new ChannelOutboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext ctx) {
                ctx.channel().closeFuture().addListener(f -> channelClosed[0] = true);
                allocated[0] = ctx.alloc().directBuffer().writeByte(1);
                throw failure;
            }
            @Override public void handlerRemoved(ChannelHandlerContext ctx) { allocated[0].release(); }
        };
        RuntimeException actual = assertThrows(RuntimeException.class, () ->
            NettyPayloadEncoder.encode(handler, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        assertSame(failure, actual.getCause());
        assertTrue(channelClosed[0]);
        assertEquals(0, allocated[0].refCnt());
    }

    @Test void channelClosedBetweenBlocksDoesNotLeakUnacceptedInputBuffer() {
        TrackingAllocator allocator = new TrackingAllocator();
        var handler = new ChannelOutboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext ctx) {
                ctx.channel().config().setAllocator(allocator);
            }
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
                ctx.close();
            }
        };
        assertThrows(IOException.class, () -> NettyPayloadEncoder.encode(handler,
            new ByteArrayInputStream(new byte[16384]), new ByteArrayOutputStream()));
        assertFalse(allocator.buffers.isEmpty());
        allocator.buffers.forEach(buffer -> assertEquals(0, buffer.refCnt()));
    }

    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();
        @Override protected ByteBuf newHeapBuffer(int initial, int max) {
            ByteBuf buffer = new UnpooledHeapByteBuf(this, initial, max);
            buffers.add(buffer);
            return buffer;
        }
        @Override protected ByteBuf newDirectBuffer(int initial, int max) {
            ByteBuf buffer = new UnpooledDirectByteBuf(this, initial, max);
            buffers.add(buffer);
            return buffer;
        }
        @Override public boolean isDirectBufferPooled() { return false; }
    }

    private static final class TrackingEncoder extends MessageToMessageEncoder<ByteBuf> {
        private final List<ByteBuf> buffers = new ArrayList<>();
        private boolean removed;

        @Override protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            for (int i = 0; i < 2; i++) {
                ByteBuf buffer = ctx.alloc().directBuffer().writeByte(i);
                buffers.add(buffer);
                out.add(buffer);
            }
        }

        @Override public void handlerRemoved(ChannelHandlerContext ctx) { removed = true; }
    }
}
