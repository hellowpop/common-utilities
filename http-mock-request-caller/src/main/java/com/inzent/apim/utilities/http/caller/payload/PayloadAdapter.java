package com.inzent.apim.utilities.http.caller.payload;

import com.inzent.apim.utilities.http.caller.transport.BodyStore;
import com.inzent.apim.utilities.http.payload.*;
import com.aayushatharva.brotli4j.Brotli4jLoader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.*;
import io.netty.handler.codec.http.HttpHeaders;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Consumes generator callback streams while they are alive; all work runs on the caller thread. */
public final class PayloadAdapter {
    private final long threshold, maximum;
    public PayloadAdapter() { this(256 * 1024, 1024L * 1024 * 1024); }
    public PayloadAdapter(long memoryThreshold, long maxBytes) { threshold = memoryThreshold; maximum = maxBytes; }
    public PreparedPayload prepare(PayloadSpec spec, Path directory) throws IOException {
        BodyStore store = BodyStore.create(directory, threshold, maximum);
        Map<String,List<String>> headers = new LinkedHashMap<>();
        try {
            new DefaultHttpPayloadGenerator().generate(spec.chunked(),spec.contentType(),spec.contentEncoding(),
                    spec.contentLength(),spec.blockSize(),(h,in) -> {
                        copyHeaders(h,headers);
                        try { copy(in,store); } catch (IOException e) { throw new UncheckedIOException(e); }
                    });
            store.finish(); return new PreparedPayload(headers,store);
        } catch (Throwable e) { cleanup(store,e); throw e; }
    }
    public PreparedPayload override(PayloadSpec spec, byte[] rawBody, Path directory) throws IOException {
        Objects.requireNonNull(rawBody);
        if (rawBody.length > 1024 * 1024) throw new IllegalArgumentException("Body override exceeds 1 MiB");
        BodyStore store = BodyStore.create(directory,threshold,maximum);
        try {
            if (spec.contentEncoding() == ContentEncoding.IDENTITY) store.write(rawBody,0,rawBody.length);
            else encode(spec.contentEncoding(),rawBody,store);
            store.finish();
            Map<String,List<String>> headers = new LinkedHashMap<>();
            headers.put("content-type",List.of(spec.contentType().mediaType()));
            if (spec.contentEncoding() != ContentEncoding.IDENTITY) headers.put("content-encoding",List.of(spec.contentEncoding().token()));
            headers.put(spec.chunked()?"transfer-encoding":"content-length",List.of(spec.chunked()?"chunked":Long.toString(store.size())));
            return new PreparedPayload(headers,store);
        } catch (Throwable e) { cleanup(store,e); throw e; }
    }
    private static void cleanup(BodyStore store, Throwable failure) {
        try { store.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
    }
    private static void copyHeaders(HttpHeaders source, Map<String,List<String>> target) {
        for (String name: source.names()) target.put(name.toLowerCase(Locale.ROOT),List.copyOf(source.getAll(name)));
    }
    private static void copy(InputStream in, BodyStore store) throws IOException {
        byte[] block = new byte[8192]; int n;
        while ((n=in.read(block)) != -1) { cancelled(); store.write(block,0,n); }
    }
    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Payload preparation cancelled");
    }
    private static ChannelHandler encoder(ContentEncoding encoding) {
        return switch (encoding) {
            case GZIP -> new JdkZlibEncoder(ZlibWrapper.GZIP);
            case DEFLATE -> new JdkZlibEncoder(ZlibWrapper.ZLIB);
            case BR -> { Brotli4jLoader.ensureAvailability(); yield new BrotliEncoder(); }
            case ZSTD -> new ZstdEncoder();
            case SNAPPY -> new SnappyFrameEncoder();
            case IDENTITY -> throw new IllegalArgumentException("No identity codec");
        };
    }
    private static void encode(ContentEncoding encoding, byte[] bytes, BodyStore store) throws IOException {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.pipeline().addLast(encoder(encoding)); channel.checkException();
            for (int offset=0;offset<bytes.length;offset+=8192) {
                cancelled(); int n=Math.min(8192,bytes.length-offset);
                channel.writeAndFlush(channel.alloc().buffer(n).writeBytes(bytes,offset,n)).addListener(f -> {
                    if (!f.isSuccess()) channel.pipeline().fireExceptionCaught(f.cause());
                });
                channel.runPendingTasks(); channel.checkException(); drain(channel,store);
            }
            channel.finish(); drain(channel,store);
        } finally {
            try { channel.finishAndReleaseAll(); }
            finally { try {channel.releaseInbound();} finally {channel.releaseOutbound();} }
        }
    }
    private static void drain(EmbeddedChannel channel, BodyStore store) throws IOException {
        ByteBuf buffer;
        while ((buffer=channel.readOutbound()) != null) {
            try {
                byte[] block=new byte[Math.min(8192,buffer.readableBytes())];
                while (buffer.isReadable()) {int n=Math.min(block.length,buffer.readableBytes());buffer.readBytes(block,0,n);store.write(block,0,n);}
            } finally { buffer.release(); }
        }
    }
}
