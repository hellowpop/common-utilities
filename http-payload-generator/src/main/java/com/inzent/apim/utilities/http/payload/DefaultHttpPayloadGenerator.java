package com.inzent.apim.utilities.http.payload;

import io.netty.handler.codec.http.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Stateless, reusable generator. Callbacks must consume the stream before returning. */
public final class DefaultHttpPayloadGenerator implements HttpPayloadGenerator {
    @Override public void generate(boolean chunked, ContentType contentType, ContentEncoding contentEncoding, long contentLength,
                                   long blockSize, BiConsumer<HttpHeaders, InputStream> acceptor) {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(contentEncoding, "contentEncoding");
        Objects.requireNonNull(acceptor, "acceptor");
        contentType.validate(contentLength, blockSize);
        if (contentEncoding == ContentEncoding.IDENTITY) {
            contentType.generate(contentLength, blockSize,
                in -> acceptor.accept(headers(chunked, contentType, contentEncoding, contentLength), in));
            return;
        }
        try (TemporaryPayload payload = new TemporaryPayload()) {
            contentType.generate(contentLength, blockSize, in -> encode(contentEncoding, in, payload.path));
            try (InputStream in = Files.newInputStream(payload.path)) {
                acceptor.accept(headers(chunked, contentType, contentEncoding, Files.size(payload.path)), in);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static HttpHeaders headers(boolean chunked, ContentType contentType, ContentEncoding encoding, long length) {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.CONTENT_TYPE, contentType.mediaType());
        if (encoding != ContentEncoding.IDENTITY) headers.set(HttpHeaderNames.CONTENT_ENCODING, encoding.token());
        if (chunked) headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        else headers.set(HttpHeaderNames.CONTENT_LENGTH, length);
        return headers;
    }

    private static void encode(ContentEncoding encoding, InputStream in, Path path) {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            NettyPayloadEncoder.encode(encoding.encoder(), in, out);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static final class TemporaryPayload implements AutoCloseable {
        private final Path path = Files.createTempFile("http-payload-", ".bin");
        TemporaryPayload() throws IOException { }
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }
}
