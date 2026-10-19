package com.inzent.apim.utilities.http.curl;

import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextStorageTest {
    @TempDir Path temp;

    // Missing spill or an off-by-one must fail at the boundary, with identical bytes on reread.
    @Test void bodiesSpillOnlyAboveOneMiBAndReleaseReaders() throws Exception {
        for (int length : new int[]{1048575,1048576,1048577}) {
            byte[] source = new byte[length]; Arrays.fill(source, (byte) 71);
            BodyStore body = new BodyStore(temp);
            body.copyFrom(new ByteArrayInputStream(source));
            assertEquals(length, body.size());
            assertEquals(length > 1048576, body.isFileBacked());
            try (InputStream in = body.openStream()) { assertArrayEquals(source, in.readAllBytes()); }
            InputStream reader = body.openStream();
            body.close();
            assertThrows(IOException.class, reader::read);
            assertThrows(IOException.class, body::openStream);
            body.close();
        }
        try (var paths = Files.list(temp)) { assertEquals(0, paths.count()); }
    }

    @Test void builderCopiesHeadersValuesAndConfiguration() throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders().add("X-Old", "first");
        List<String> arguments = new ArrayList<>(List.of("--ipv4"));
        var config = ExecuteConfigure.builder().additionalArguments(arguments).build();
        arguments.clear();
        StringBuilder mutable = new StringBuilder("original");
        var builder = ExecuteContext.builder().url(URI.create("http://localhost/"))
            .configure(config).requestHeader("X-Replaced", "gone").requestHeaders(headers)
            .requestHeader("X-Value", mutable).requestHeader("X-Old", "second");
        headers.clear(); mutable.append("changed");
        try (ExecuteContext first = builder.build(); ExecuteContext second = builder.build()) {
            first.requestHeaders().set("X-Old", "modified");
            assertEquals(List.of("first", "second"), second.requestHeaders().getAll("X-Old"));
            assertEquals("original", second.requestHeaders().get("X-Value"));
            assertFalse(second.requestHeaders().contains("X-Replaced"));
            assertEquals(List.of("--ipv4"), second.configure().additionalArguments());
            assertFalse(second.configure().verifyCertificate());
        }
    }

    @Test void invalidHeadersAndNullsFailBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> ExecuteContext.builder().requestHeader("X-Test", "a\r\nb"));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().requestHeader("X", null));
        assertThrows(NullPointerException.class, () -> ExecuteContext.builder().requestHeaders(null));
        assertThrows(NullPointerException.class, () -> ExecuteConfigure.builder().additionalArguments(Arrays.asList((String)null)).build());
    }

    @Test void contextOwnsItsFilesButNotTemporaryParent() throws Exception {
        ExecuteContext context = ExecuteContext.builder().url(URI.create("http://localhost/"))
            .configure(ExecuteConfigure.builder().temporaryDirectory(temp).build()).build();
        Path directory = context.directory();
        BodyStore body = context.newBody();
        body.copyFrom(new ByteArrayInputStream(new byte[1048577]));
        context.close(); context.close();
        assertFalse(Files.exists(directory));
        assertTrue(Files.exists(temp));
        assertThrows(IllegalStateException.class, context::directory);
    }

    @Test void contextClosesSharedResponseReadersBeforeDeletingStdout() throws Exception {
        ExecuteContext context = ExecuteContext.builder().url(URI.create("http://localhost/"))
            .configure(ExecuteConfigure.builder().temporaryDirectory(temp).build()).build();
        ProcessOutputRecord stdout = context.newOutput("stdout.bin");
        Files.write(stdout.path(),new byte[1048577]); stdout.finish();
        BodyStore body = context.referenceBody(stdout.path());
        InputStream retained = body.openStream();
        context.close();
        assertThrows(IOException.class,retained::read);
        assertFalse(Files.exists(stdout.path()));
    }

    @Test void cleanupContinuesAfterFailureAndCanBeRetried() throws Exception {
        ExecuteContext context = ExecuteContext.builder().url(URI.create("http://localhost/"))
            .configure(ExecuteConfigure.builder().temporaryDirectory(temp).build()).build();
        Path directory = context.directory();
        ProcessOutputRecord stdout = context.newOutput("stdout.bin");
        InputStream reader = stdout.openStream();
        Path foreign = Files.writeString(directory.resolve("caller-owned"),"keep");
        assertThrows(IOException.class,context::close);
        assertThrows(IOException.class,reader::read);
        assertFalse(Files.exists(stdout.path()));
        assertEquals("keep",Files.readString(foreign));
        Files.delete(foreign);
        context.close(); assertFalse(Files.exists(directory));
    }
}
