package com.inzent.apim.utilities.http.payload;

import io.netty.handler.codec.http.HttpHeaders;
import java.io.InputStream;
import java.util.function.BiConsumer;

/** Produces HTTP entity bytes and headers, without HTTP message framing. */
@FunctionalInterface
public interface HttpPayloadGenerator {
    void generate(boolean chunked, ContentType contentType, ContentEncoding contentEncoding, long contentLength,
                  long blockSize, BiConsumer<HttpHeaders, InputStream> acceptor);
}
