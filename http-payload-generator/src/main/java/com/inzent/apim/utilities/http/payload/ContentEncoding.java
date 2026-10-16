package com.inzent.apim.utilities.http.payload;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.compression.*;

/** Content codings implemented by Netty, plus the unencoded representation. */
public enum ContentEncoding {
    IDENTITY("identity"), GZIP("gzip"), DEFLATE("deflate"), BR("br"), ZSTD("zstd"), SNAPPY("snappy");

    private final String token;
    ContentEncoding(String token) { this.token = token; }
    public String token() { return token; }

    ChannelHandler encoder() {
        return switch (this) {
            case GZIP -> new JdkZlibEncoder(ZlibWrapper.GZIP);
            case DEFLATE -> new JdkZlibEncoder(ZlibWrapper.ZLIB);
            case BR -> {
                Brotli4jLoader.ensureAvailability();
                yield new BrotliEncoder();
            }
            case ZSTD -> new ZstdEncoder();
            case SNAPPY -> new SnappyFrameEncoder();
            case IDENTITY -> throw new IllegalStateException("identity does not need an encoder");
        };
    }
}
