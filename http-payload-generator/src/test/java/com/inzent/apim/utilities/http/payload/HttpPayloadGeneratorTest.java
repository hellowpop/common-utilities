package com.inzent.apim.utilities.http.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpPayloadGeneratorTest {
    @Test void keysAreIndependentOfDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar"));
            ContentType.APPLICATION_XML.generate(99, 7, in -> {
                try {
                    var root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement();
                    assertEquals("key0", root.getFirstChild().getNodeName());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally { java.util.Locale.setDefault(previous); }
    }

    private static byte[] read(InputStream in) {
        try { return in.readAllBytes(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @ParameterizedTest @EnumSource(ContentType.class)
    void exactLengthIncludingSmallRemainders(ContentType type) {
        for (int size = type.minimumLength(); size < 240; size++) {
            int expected = size;
            type.generate(size, 7, in -> assertEquals(expected, read(in).length));
        }
        type.generate(0, 7, in -> assertEquals(0, read(in).length));
    }

    @Test void structuredContentRemainsParseableAndValuesAreBounded() {
        for (int size = 2; size < 200; size++) {
            ContentType.APPLICATION_JSON.generate(size, 7, in -> {
                try {
                    var json = new ObjectMapper().readTree(in);
                    json.properties().forEach(e -> assertTrue(e.getValue().asText().length() <= 7));
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        ContentType.APPLICATION_JSON.generate(25, 7, in -> {
            try { assertEquals("aaaaaaa", new ObjectMapper().readTree(in).get("key0").asText()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        ContentType.APPLICATION_XML.generate(99, 7, in -> {
            try { assertEquals("root", DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement().getNodeName()); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test void longLengthsAreLazyAndArgumentsAreValidated() {
        ContentType.APPLICATION_JSON.generate(Long.MAX_VALUE, Long.MAX_VALUE, in -> {
            try { assertEquals(64, in.readNBytes(64).length); } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        assertThrows(IllegalArgumentException.class, () -> ContentType.APPLICATION_JSON.generate(1, 7, in -> fail()));
        assertThrows(IllegalArgumentException.class, () -> ContentType.TEXT_PLAIN.generate(-1, 7, in -> fail()));
        assertThrows(IllegalArgumentException.class, () -> ContentType.TEXT_PLAIN.generate(10, 0, in -> fail()));
    }

    @ParameterizedTest @EnumSource(ContentEncoding.class)
    void encodingRoundTripsAndHeadersDescribeEncodedBytes(ContentEncoding encoding) {
        var generator = new DefaultHttpPayloadGenerator();
        AtomicReference<byte[]> raw = new AtomicReference<>();
        ContentType.APPLICATION_JSON.generate(1024, 17, in -> raw.set(read(in)));
        for (boolean chunked : new boolean[] {false, true}) {
            generator.generate(chunked, ContentType.APPLICATION_JSON, encoding, 1024, 17, (headers, in) -> {
                byte[] encoded = read(in);
                assertEquals("application/json; charset=UTF-8", headers.get(HttpHeaderNames.CONTENT_TYPE));
                assertEquals(encoding == ContentEncoding.IDENTITY ? null : encoding.token(), headers.get(HttpHeaderNames.CONTENT_ENCODING));
                if (chunked) {
                    assertEquals("chunked", headers.get(HttpHeaderNames.TRANSFER_ENCODING));
                    assertFalse(headers.contains(HttpHeaderNames.CONTENT_LENGTH));
                } else {
                    assertEquals(encoded.length, Long.parseLong(headers.get(HttpHeaderNames.CONTENT_LENGTH)));
                    assertFalse(headers.contains(HttpHeaderNames.TRANSFER_ENCODING));
                }
                assertArrayEquals(raw.get(), decode(encoding, encoded));
            });
        }
    }

    private byte[] decode(ContentEncoding encoding, byte[] bytes) {
        if (encoding == ContentEncoding.IDENTITY) return bytes;
        var decoder = switch (encoding) {
            case GZIP -> new JdkZlibDecoder(ZlibWrapper.GZIP);
            case DEFLATE -> new JdkZlibDecoder(ZlibWrapper.ZLIB);
            case BR -> new BrotliDecoder();
            case ZSTD -> new ZstdDecoder();
            case SNAPPY -> new SnappyFrameDecoder();
            default -> throw new AssertionError();
        };
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(bytes));
            channel.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuf buffer;
            while ((buffer = channel.readInbound()) != null) {
                try { byte[] part = new byte[buffer.readableBytes()]; buffer.readBytes(part); out.writeBytes(part); }
                finally { buffer.release(); }
            }
            return out.toByteArray();
        } finally { channel.finishAndReleaseAll(); }
    }

    @ParameterizedTest @EnumSource(ContentEncoding.class)
    void emptyAndMultipleBufferPayloadsRoundTrip(ContentEncoding encoding) {
        for (int size : new int[] {0, 1, 8191, 8192, 8193, 200000}) {
            new DefaultHttpPayloadGenerator()
                .generate(false, ContentType.APPLICATION_OCTET_STREAM, encoding, size, Long.MAX_VALUE, (h, in) -> {
                    byte[] expected = new byte[size];
                    java.util.Arrays.fill(expected, (byte) 'a');
                    assertArrayEquals(expected, decode(encoding, read(in)));
                });
        }
    }

    @Test void directStreamContractAndNullValidation() {
        AtomicReference<InputStream> reference = new AtomicReference<>();
        ContentType.TEXT_PLAIN.generate(100, 5, in -> {
            reference.set(in);
            try {
                assertEquals(0, in.read(new byte[1], 0, 0));
                assertEquals('k', in.read());
                assertEquals(99, in.readAllBytes().length);
                assertEquals(-1, in.read());
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        assertThrows(IOException.class, () -> reference.get().read());
        var generator = new DefaultHttpPayloadGenerator();
        assertThrows(NullPointerException.class, () -> generator.generate(false, null, ContentEncoding.GZIP, 10, 5, (h, in) -> fail()));
        assertThrows(NullPointerException.class, () -> generator.generate(false, ContentType.TEXT_PLAIN, null, 10, 5, (h, in) -> fail()));
        assertThrows(NullPointerException.class, () -> generator.generate(false, ContentType.TEXT_PLAIN, ContentEncoding.GZIP, 10, 5, null));
    }

    @Test void emptyZstdCanBeReadByIndependentDecoder() {
        new DefaultHttpPayloadGenerator()
            .generate(false, ContentType.TEXT_PLAIN, ContentEncoding.ZSTD, 0, 7, (headers, in) -> {
                try (var decoded = new com.github.luben.zstd.ZstdInputStream(in)) {
                    assertEquals(-1, decoded.read());
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
    }

    @Test void streamClosesEvenWhenCallbackThrows() {
        for (ContentEncoding encoding : ContentEncoding.values()) {
            AtomicReference<InputStream> reference = new AtomicReference<>();
            var failure = new IllegalStateException("consumer failure");
            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                new DefaultHttpPayloadGenerator().generate(false, ContentType.TEXT_PLAIN, encoding, 20, 7, (h, in) -> {
                    reference.set(in); throw failure;
                })));
            assertThrows(IOException.class, () -> reference.get().read());
        }
    }

    @ParameterizedTest @EnumSource(ContentEncoding.class)
    void oneGeneratorSelectsContentTypeOnEveryCall(ContentEncoding encoding) {
        HttpPayloadGenerator generator = new DefaultHttpPayloadGenerator();
        for (ContentType type : ContentType.values()) {
            String prefix = switch (type) {
                case TEXT_PLAIN -> "key0=";
                case APPLICATION_JSON -> "{\"key0\":\"";
                case APPLICATION_XML -> "<root><key0>";
                case TEXT_HTML -> "<html><body><div data-key=\"key0\">";
                case APPLICATION_FORM_URLENCODED -> "key0=";
                case APPLICATION_OCTET_STREAM -> "aaaa";
            };
            generator.generate(false, type, encoding, 256, 7, (headers, in) -> {
                byte[] body = decode(encoding, read(in));
                assertEquals(256, body.length);
                assertEquals(type.mediaType(), headers.get(HttpHeaderNames.CONTENT_TYPE));
                assertTrue(new String(body, java.nio.charset.StandardCharsets.US_ASCII).startsWith(prefix));
            });
        }
    }
}
