package com.inzent.apim.utilities.http.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SequentialDummyContentTest {
    @ParameterizedTest @EnumSource(ContentType.class)
    void bulkReadsProduceAlphanumericValues(ContentType type) { verifyValues(type, false); }

    @ParameterizedTest @EnumSource(ContentType.class)
    void singleByteReadsProduceAlphanumericValues(ContentType type) { verifyValues(type, true); }

    private static void verifyValues(ContentType type, boolean singleByte) {
        type.generate(20000, 257, in -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (singleByte) {
                    int value;
                    while ((value = in.read()) != -1) out.write(value);
                } else {
                    byte[] buffer = new byte[37];
                    int count;
                    while ((count = in.read(buffer, 3, 31)) != -1) out.write(buffer, 3, count);
                }
                assertEquals(20000, out.size());
                String body = out.toString(StandardCharsets.US_ASCII);
                String values;
                if (type == ContentType.APPLICATION_OCTET_STREAM) values = body;
                else {
                    String expression = switch (type) {
                        case APPLICATION_JSON -> "\"key[0-9]+\":\"([^\"]*)\"";
                        case APPLICATION_XML -> "<key[0-9]+>([^<]*)</key[0-9]+>";
                        case TEXT_HTML -> "<div data-key=\"key[0-9]+\">([^<]*)</div>";
                        case TEXT_PLAIN -> "key[0-9]+=([^\\n]*)\\n";
                        case APPLICATION_FORM_URLENCODED -> "key[0-9]+=([^&]*)";
                        case MULTIPART_FORM_DATA -> "\\r\\n\\r\\n([A-Za-z0-9]*)\\r\\n";
                        default -> throw new AssertionError();
                    };
                    StringBuilder joined = new StringBuilder();
                    var matches = Pattern.compile(expression).matcher(body);
                    while (matches.find()) {
                        assertTrue(matches.group(1).length() <= 257);
                        joined.append(matches.group(1));
                    }
                    values = joined.toString();
                }
                assertTrue(values.length() > 10000);
                assertTrue(values.matches("[A-Za-z0-9]+"));
                assertTrue(values.chars().anyMatch(c -> c >= 'A' && c <= 'Z'), "uppercase missing");
                assertTrue(values.chars().anyMatch(c -> c >= 'a' && c <= 'z'), "lowercase missing");
                assertTrue(values.chars().anyMatch(c -> c >= '0' && c <= '9'), "digits missing");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @ParameterizedTest @EnumSource(ContentType.class)
    void sameArgumentsProduceSameBytesForEveryReadMode(ContentType type) {
        for (int length : new int[] {0, Math.max(1, type.minimumLength()), 256, 8193}) {
            for (long blockSize : new long[] {1, 7, Long.MAX_VALUE}) {
                byte[] expected = bytes(type, length, blockSize, 0);
                assertArrayEquals(expected, bytes(type, length, blockSize, 0));
                assertArrayEquals(expected, bytes(type, length, blockSize, 1));
                assertArrayEquals(expected, bytes(type, length, blockSize, 2));
            }
        }
    }

    @Test void sequenceWrapsAndContinuesAcrossValues() {
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz01234567",
            new String(bytes(ContentType.APPLICATION_OCTET_STREAM, 70, 7, 0), StandardCharsets.US_ASCII));
        assertEquals("key0=012\nkey1=345\nkey2=678\n",
            new String(bytes(ContentType.TEXT_PLAIN, 27, 3, 0), StandardCharsets.US_ASCII));
    }

    private static byte[] bytes(ContentType type, long length, long blockSize, int mode) {
        AtomicReference<byte[]> result = new AtomicReference<>();
        type.generate(length, blockSize, in -> {
            try {
                if (mode == 0) { result.set(in.readAllBytes()); return; }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[37];
                int value;
                while ((value = in.read()) != -1) {
                    out.write(value);
                    if (mode == 2) {
                        int count = in.read(buffer, 3, 31);
                        if (count == -1) break;
                        out.write(buffer, 3, count);
                    }
                }
                result.set(out.toByteArray());
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        return result.get();
    }
}
