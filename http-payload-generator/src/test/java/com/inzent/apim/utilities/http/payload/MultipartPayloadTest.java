package com.inzent.apim.utilities.http.payload;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MultipartPayloadTest {
    @Test void multipartContainsDecodableTextAndFileWithExactLength() {
        ContentType type = ContentType.MULTIPART_FORM_DATA;
        for (int length : new int[] {type.minimumLength(), type.minimumLength() + 1, 256, 1024, 20000}) {
            for (long block : new long[] {1, 7, Long.MAX_VALUE}) {
                new DefaultHttpPayloadGenerator().generate(false, type, ContentEncoding.IDENTITY, length, block, (headers, in) -> {
                    byte[] bytes = read(in);
                    assertEquals(length, bytes.length);
                    assertEquals(length, headers.getInt(HttpHeaderNames.CONTENT_LENGTH));
                    FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.wrappedBuffer(bytes));
                    request.headers().set(headers);
                    withDecoder(request, 1000, decoder -> {
                      try {
                        var parts = decoder.getBodyHttpDatas();
                        assertTrue(parts.size() >= 2);
                        assertInstanceOf(Attribute.class, parts.get(0));
                        FileUpload file = assertInstanceOf(FileUpload.class, parts.get(1));
                        assertEquals("file1.bin", file.getFilename());
                        assertEquals("application/octet-stream", file.getContentType());
                        StringBuilder values = new StringBuilder();
                        for (InterfaceHttpData part : parts) {
                            HttpData data = (HttpData) part;
                            assertTrue(data.length() <= block);
                            values.append(data.getString());
                        }
                        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
                        for (int i = 0; i < values.length(); i++) assertEquals(alphabet.charAt(i % 62), values.charAt(i));
                        if (length == 1024) {
                            assertTrue(((HttpData) parts.get(0)).length() > 0);
                            assertTrue(file.length() > 0);
                        }
                      } catch (IOException e) { throw new UncheckedIOException(e); }
                    });
                    assertEquals(0, request.refCnt());
                });
            }
        }
    }

    @Test void decoderInitializationFailureReleasesRequestBuffer() {
        FullHttpRequest request = multipartRequest();
        List<FileUpload> uploads = new ArrayList<>();
        DefaultHttpDataFactory factory = new DefaultHttpDataFactory() {
            @Override public FileUpload createFileUpload(HttpRequest owner, String name, String filename,
                    String contentType, String transferEncoding, Charset charset, long size) {
                FileUpload upload = super.createFileUpload(owner, name, filename, contentType, transferEncoding, charset, size);
                uploads.add(upload);
                return upload;
            }
        };
        try {
            assertThrows(HttpPostRequestDecoder.TooManyFormFieldsException.class,
                () -> withDecoder(request, 1, factory, decoder -> fail("decoder initialization must fail")));
            assertEquals(0, request.refCnt(), "request leaked when decoder construction failed");
            assertFalse(uploads.isEmpty());
            for (FileUpload upload : uploads) assertEquals(0, upload.refCnt(), "file part leaked during decoder construction");
        } finally {
            if (request.refCnt() > 0) request.release();
            for (FileUpload upload : uploads) if (upload.refCnt() > 0) upload.release();
        }
    }

    @Test void decoderConsumerNpeReleasesRequestAndParsedPartBuffers() {
        FullHttpRequest request = multipartRequest();
        AtomicReference<List<InterfaceHttpData>> parts = new AtomicReference<>();
        NullPointerException failure = new NullPointerException("decoder consumer failed");
        try {
            assertSame(failure, assertThrows(NullPointerException.class, () -> withDecoder(request, 1000, decoder -> {
                parts.set(List.copyOf(decoder.getBodyHttpDatas()));
                throw failure;
            })));
            assertEquals(0, request.refCnt());
            assertNotNull(parts.get());
            for (InterfaceHttpData part : parts.get()) assertEquals(0, part.refCnt());
        } finally {
            try {
                if (parts.get() != null) {
                    for (InterfaceHttpData part : parts.get()) if (part.refCnt() > 0) part.release();
                }
            } finally {
                if (request.refCnt() > 0) request.release();
            }
        }
    }

    private static FullHttpRequest multipartRequest() {
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        ContentType.MULTIPART_FORM_DATA.generate(1024, 17, in -> bytes.set(read(in)));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/",
            Unpooled.wrappedBuffer(bytes.get()));
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, ContentType.MULTIPART_FORM_DATA.mediaType());
        return request;
    }

    private static void withDecoder(FullHttpRequest request, int maxFields, Consumer<HttpPostRequestDecoder> consumer) {
        // Mixed-mode factory tracks every part, including one rejected before it enters the decoder's body list.
        withDecoder(request, maxFields, new DefaultHttpDataFactory(), consumer);
    }

    private static void withDecoder(FullHttpRequest request, int maxFields, DefaultHttpDataFactory factory,
                                    Consumer<HttpPostRequestDecoder> consumer) {
        try {
            HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(factory, request,
                java.nio.charset.StandardCharsets.UTF_8, maxFields, 1024 * 1024);
            try { consumer.accept(decoder); }
            finally { decoder.destroy(); }
        } finally { request.release(); }
    }

    @Test void multipartRejectsTruncatedDocumentsAndClosesLazyStreams() {
        ContentType type = ContentType.MULTIPART_FORM_DATA;
        for (int size = 1; size < type.minimumLength(); size++) {
            int length = size;
            assertThrows(IllegalArgumentException.class, () -> type.generate(length, 7, in -> fail()));
        }
        type.generate(0, 7, in -> assertEquals(0, read(in).length));
        AtomicReference<InputStream> stream = new AtomicReference<>();
        type.generate(Long.MAX_VALUE, Long.MAX_VALUE, in -> {
            stream.set(in);
            try { assertEquals(1024, in.readNBytes(1024).length); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        assertThrows(IOException.class, () -> stream.get().read());
    }

    private static byte[] read(InputStream in) {
        try { return in.readAllBytes(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
