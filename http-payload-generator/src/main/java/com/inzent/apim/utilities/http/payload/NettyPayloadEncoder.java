package com.inzent.apim.utilities.http.payload;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** Owns codec/channel resources; input and output streams remain owned by the caller. */
final class NettyPayloadEncoder {
    private NettyPayloadEncoder() { }

    static void encode(ChannelHandler encoder, InputStream in, OutputStream out) throws IOException {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        try (ChannelResources resources = new ChannelResources()) {
            EmbeddedChannel channel = resources.channel;
            // Add the handler after the channel is owned by the resource scope.
            // EmbeddedChannel(handler) uses a ChannelInitializer which can swallow initialization failures.
            channel.pipeline().addLast(encoder);
            channel.checkException();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                // Channel.writeAndFlush owns the message even when the channel is already closed.
                // EmbeddedChannel.writeOutbound can reject it before taking ownership.
                channel.writeAndFlush(channel.alloc().buffer(count).writeBytes(buffer, 0, count))
                    .addListener(future -> {
                        if (!future.isSuccess()) channel.pipeline().fireExceptionCaught(future.cause());
                    });
                channel.runPendingTasks();
                channel.checkException();
                drain(channel, out);
            }
            channel.finish();
            drain(channel, out);
        }
    }

    private static void drain(EmbeddedChannel channel, OutputStream out) throws IOException {
        ByteBuf buffer;
        while ((buffer = channel.readOutbound()) != null) {
            try { buffer.readBytes(out, buffer.readableBytes()); }
            finally { buffer.release(); }
        }
    }

    private static final class ChannelResources implements AutoCloseable {
        private final EmbeddedChannel channel = new EmbeddedChannel();

        @Override public void close() {
            try {
                channel.finishAndReleaseAll();
            } finally {
                // A failure during close/pending tasks can precede Netty's own queue cleanup.
                try { channel.releaseInbound(); }
                finally { channel.releaseOutbound(); }
            }
        }
    }
}
