package com.inzent.apim.utilities.http.curl;

import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArgumentsParserTest {
    @TempDir Path temp;
    @Test void additionalTokensPreserveOrderAndMaskSecrets() throws Exception {
        var configure = ExecuteConfigure.builder().additionalArguments(List.of(
            "--resolve", "test:80:127.0.0.1", "--resolve", "test:81:127.0.0.1",
            "--user-agent", "한글 agent", "--user", "name:secret", "--noproxy", "")).build();
        try (var c = ExecuteContext.builder().url(URI.create("http://test/")).configure(configure).build()) {
            CurlArguments.validate(c);
            List<String> args = CurlArguments.build(c, new DefaultHttpHeaders());
            assertEquals("--disable", args.get(1));
            assertTrue(args.contains("--insecure"));
            assertEquals("한글 agent", args.get(args.indexOf("--user-agent")+1));
            assertEquals(2, Collections.frequency(args, "--resolve"));
            assertFalse(CurlArguments.mask(args).contains("name:secret"));
            assertEquals("name:secret", args.get(args.indexOf("--user")+1));
        }
    }

    @Test void managedAndMalformedArgumentsAreRejected() throws Exception {
        for (List<String> args : List.of(List.of("--output", "file"), List.of("--max-time", "9"),
                List.of("--insecure"), List.of("--header", "X:a"), List.of("-k"), List.of("--resolve=x"),
                List.of("--resolve"), List.of("http://other/"), List.of("--ipv4", "--ipv6"),
                List.of("--user", "name"), List.of("--ipv4", "--ipv4"), List.of("--user-agent", "x\ny"))) {
            try (var c = ExecuteContext.builder().url(URI.create("http://test/"))
                    .configure(ExecuteConfigure.builder().additionalArguments(args).build()).build()) {
                assertThrows(IllegalArgumentException.class, () -> CurlArguments.validate(c), args.toString());
            }
        }
    }

    @Test void parserKeepsInterimAndFinalHeadersAndDuplicateValues() throws Exception {
        Path log = temp.resolve("stderr.log");
        Files.writeString(log, "* Connected\n> POST / HTTP/1.1\n> Host: test\n> \n< HTTP/1.1 100 Continue\n< \n< HTTP/1.1 201 Created\n< Set-Cookie: a=1\n< Set-Cookie: b=2\n< \n", StandardCharsets.ISO_8859_1);
        var record = new ExecutionRecord();
        CurlLogParser.parse(log, record);
        assertEquals(1, record.request().sentHeaders().size());
        assertEquals("test", record.request().sentHeaders().get(0).get(HttpHeaderNames.HOST));
        assertEquals(2, record.response().headerBlocks().size());
        assertEquals(201, record.response().status().orElseThrow().code());
        assertEquals(List.of("a=1", "b=2"), record.response().headers().getAll(HttpHeaderNames.SET_COOKIE));
        assertThrows(UnsupportedOperationException.class, () -> record.response().headers().clear());
    }

    @Test void parserBoundsMemoryAndKeepsRawLog() throws Exception {
        Path log = temp.resolve("large.log");
        Files.writeString(log, "* "+"d".repeat(70000)+"\n< HTTP/1.1 200 OK\n< X-Long: "+"h".repeat(262144)+"\n< \n");
        long original = Files.size(log);
        var record = new ExecutionRecord();
        assertThrows(java.io.IOException.class, () -> CurlLogParser.parse(log,record));
        assertTrue(record.headersTruncated());
        assertTrue(record.diagnosticsTruncated());
        assertTrue(record.diagnostics().getBytes(StandardCharsets.ISO_8859_1).length <= 65536);
        assertEquals(original, Files.size(log));
    }

    @Test void windowsVerboseCarriageReturnsDoNotBecomeHeaderContent() throws Exception {
        Path log = temp.resolve("windows.log");
        Files.write(log,"< HTTP/1.1 200 OK\r\r\n< X-Test: value\r\r\n< \r\r\n".getBytes(StandardCharsets.ISO_8859_1));
        var record = new ExecutionRecord();
        CurlLogParser.parse(log,record);
        assertEquals(200,record.response().status().orElseThrow().code());
        assertEquals("value",record.response().headers().get("X-Test"));
    }
}
