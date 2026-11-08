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

class RandomDummyContentTest {
    @ParameterizedTest @EnumSource(ContentType.class)
    void bulkReadsProduceRandomAlphanumericValues(ContentType type) { verifyValues(type, false); }

    @ParameterizedTest @EnumSource(ContentType.class)
    void singleByteReadsProduceRandomAlphanumericValues(ContentType type) { verifyValues(type, true); }

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
                // Large samples make absence of an entire character class negligibly unlikely.
                assertTrue(values.chars().anyMatch(c -> c >= 'A' && c <= 'Z'), "uppercase missing");
                assertTrue(values.chars().anyMatch(c -> c >= 'a' && c <= 'z'), "lowercase missing");
                assertTrue(values.chars().anyMatch(c -> c >= '0' && c <= '9'), "digits missing");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @Test void separateCallsGenerateDifferentValues() {
        AtomicReference<byte[]> first = new AtomicReference<>();
        ContentType.APPLICATION_OCTET_STREAM.generate(512, 7, in -> {
            try { first.set(in.readAllBytes()); } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        ContentType.APPLICATION_OCTET_STREAM.generate(512, 7, in -> {
            try { assertFalse(java.util.Arrays.equals(first.get(), in.readAllBytes())); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }
}
