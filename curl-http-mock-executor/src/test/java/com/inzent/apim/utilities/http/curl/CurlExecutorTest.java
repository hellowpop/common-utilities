package com.inzent.apim.utilities.http.curl;

import com.inzent.apim.utilities.http.payload.*;
import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class CurlExecutorTest {
    @TempDir Path temp;
    HttpServer server;
    ExecutorService workers;
    URI base;
    final AtomicReference<byte[]> received = new AtomicReference<>();
    final AtomicReference<com.sun.net.httpserver.Headers> receivedHeaders = new AtomicReference<>();

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                receivedHeaders.set(exchange.getRequestHeaders());
                if (exchange.getRequestURI().getPath().equals("/reject")) {
                    exchange.sendResponseHeaders(413,2); exchange.getResponseBody().write(new byte[]{'n','o'}); return;
                }
                if (exchange.getRequestURI().getPath().equals("/slow")) {
                    exchange.sendResponseHeaders(200,0);
                    exchange.getResponseBody().write("partial".getBytes()); exchange.getResponseBody().flush();
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return;
                }
                received.set(exchange.getRequestBody().readAllBytes());
                exchange.getResponseHeaders().add("X-Response", "yes");
                if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200,-1); return; }
                int length = exchange.getRequestURI().getPath().equals("/large") ? 1048577 : 2;
                byte[] output = new byte[length]; Arrays.fill(output,(byte)'o');
                int status = exchange.getRequestURI().getPath().equals("/error") ? 503 : 200;
                exchange.sendResponseHeaders(status,length);
                exchange.getResponseBody().write(output);
            } finally { exchange.close(); }
        });
        server.start(); base = URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
    }
    @AfterEach void stopServer() { server.stop(0); workers.shutdownNow(); }
    ExecuteContext.Builder context(String path) {
        return ExecuteContext.builder().url(base.resolve(path)).configure(ExecuteConfigure.builder().temporaryDirectory(temp).build());
    }

    @Test void sendsBuilderAndPreHeadersCapturesLogsAndCleansUp() throws Exception {
        Path out, err, directory;
        InputStream retained;
        try (var c = context("/").requestHeader("X-Test", "builder").build()) {
            c.setPreExecute(() -> c.requestHeaders().set("X-Test", "pre"));
            AtomicBoolean post = new AtomicBoolean();
            c.setPostExecute(() -> {
                assertEquals("yes", c.record().response().headers().get("X-Response")); post.set(true);
            });
            new CurlHttpMockExecutor().handle(c);
            assertTrue(post.get()); assertEquals("pre", receivedHeaders.get().getFirst("X-Test"));
            assertEquals("application/json; charset=UTF-8", receivedHeaders.get().getFirst("Content-Type"));
            assertEquals("4096", receivedHeaders.get().getFirst("Content-Length"));
            assertEquals(4096, received.get().length);
            assertEquals(4096, c.record().request().stdinBytesWritten());
            assertEquals(ExecutionRecord.State.COMPLETED, c.record().state());
            assertEquals(0,c.record().exitCode().orElseThrow());
            assertEquals(200,c.record().response().status().orElseThrow().code());
            assertEquals(StageRecord.Status.SUCCEEDED,c.record().stage(ExecutionRecord.Stage.PRE).status());
            assertEquals(StageRecord.Status.SUCCEEDED,c.record().stage(ExecutionRecord.Stage.POST).status());
            out = c.record().stdout().orElseThrow().path(); err = c.record().stderr().orElseThrow().path(); directory = out.getParent();
            assertEquals("oo",Files.readString(out));
            assertTrue(Files.readString(err).contains("> POST / HTTP/1.1"));
            assertNotEquals(out,err);
            retained = c.record().stdout().orElseThrow().openStream();
            assertThrows(IllegalStateException.class, () -> new CurlHttpMockExecutor().handle(c));
        }
        assertFalse(Files.exists(out)); assertFalse(Files.exists(err)); assertFalse(Files.exists(directory));
        assertThrows(IOException.class,retained::read);
    }

    @Test void gzipAndChunkedAreIndependentFromGeneratedLength() throws Exception {
        for (boolean chunked : new boolean[]{false,true}) {
            try (var c = context("/").contentLength(8192).chunked(chunked).contentEncoding(ContentEncoding.GZIP).build()) {
                new CurlHttpMockExecutor().handle(c);
                assertEquals("gzip",receivedHeaders.get().getFirst("Content-Encoding"));
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(received.get()))) { assertEquals(8192,gzip.readAllBytes().length); }
                if (chunked) {
                    assertEquals("chunked",receivedHeaders.get().getFirst("Transfer-Encoding"));
                    assertNull(receivedHeaders.get().getFirst("Content-Length"));
                } else {
                    assertNull(receivedHeaders.get().getFirst("Transfer-Encoding"));
                    assertEquals(Integer.toString(received.get().length),receivedHeaders.get().getFirst("Content-Length"));
                }
                try (InputStream body = c.record().request().body().orElseThrow().openStream()) { assertArrayEquals(received.get(),body.readAllBytes()); }
            }
        }
    }

    @Test void allContentTypesAreSentUsingNettyMediaValues() throws Exception {
        for (ContentType type : ContentType.values()) try (var c = context("/").contentType(type).contentLength(1024).build()) {
            new CurlHttpMockExecutor().handle(c);
            assertEquals(type.mediaType(),receivedHeaders.get().getFirst("Content-Type"));
            assertEquals(1024,received.get().length);
        }
    }

    @Test void largeBodiesSpillAndResponseReusesStdout() throws Exception {
        Path stdout;
        try (var c = context("/large").contentLength(1048577).contentType(ContentType.APPLICATION_OCTET_STREAM).build()) {
            new CurlHttpMockExecutor().handle(c);
            assertTrue(c.record().request().body().orElseThrow().isFileBacked());
            var response = c.record().response().body().orElseThrow();
            assertTrue(response.isFileBacked()); assertEquals(1048577,response.size());
            stdout = c.record().stdout().orElseThrow().path();
            try (InputStream in = response.openStream()) { assertArrayEquals(Files.readAllBytes(stdout),in.readAllBytes()); }
            try (var files = Files.list(stdout.getParent())) { assertEquals(1,files.filter(p -> p.getFileName().toString().startsWith("body-")).count()); }
        }
        assertFalse(Files.exists(stdout));
    }

    @Test void headPreservesRawStdoutButResponseBodyIsEmpty() throws Exception {
        try (var c = context("/").method(HttpMethod.HEAD).contentLength(0).build()) {
            new CurlHttpMockExecutor().handle(c);
            assertEquals(0,c.record().response().body().orElseThrow().size());
            assertTrue(c.record().response().bodyComplete());
            assertTrue(Files.readString(c.record().stdout().orElseThrow().path()).contains("HTTP/1.1 200"));
        }
    }

    @Test void httpErrorsRemainCompletedTransfers() throws Exception {
        try (var c = context("/error").build()) {
            new CurlHttpMockExecutor().handle(c);
            assertEquals(503,c.record().response().status().orElseThrow().code());
            assertEquals(ExecutionRecord.State.COMPLETED,c.record().state());
            assertTrue(c.record().response().bodyComplete());
        }
    }

    @Test void timeoutPreservesPartialResponseAndCallsPost() throws Exception {
        try (var c = context("/slow").responseTimeout(Duration.ofMillis(250)).build()) {
            AtomicBoolean post = new AtomicBoolean();
            c.setPostExecute(() -> { assertTrue(c.record().failure().isPresent()); post.set(true); });
            long start = System.nanoTime();
            assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds() < 4);
            assertTrue(post.get()); assertFalse(c.record().response().bodyComplete());
            assertEquals(200,c.record().response().status().orElseThrow().code());
            assertEquals("partial",Files.readString(c.record().stdout().orElseThrow().path()));
        }
    }

    @Test void preFailureAndPostFailurePreserveFirstCause() throws Exception {
        RuntimeException first = new RuntimeException("pre"), second = new RuntimeException("post");
        try (var c = context("/").build()) {
            c.setPreExecute(() -> { throw first; }); c.setPostExecute(() -> { throw second; });
            var thrown = assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
            assertSame(first,thrown.getCause()); assertArrayEquals(new Throwable[]{second},first.getSuppressed());
            assertEquals(StageRecord.Status.SKIPPED,c.record().stage(ExecutionRecord.Stage.CURL).status());
            assertTrue(c.record().stdout().isEmpty());
        }
    }

    @Test void validationFailureSkipsHooksAndMissingCurlRecordsStartFailure() throws Exception {
        AtomicInteger hooks = new AtomicInteger();
        try (var c = context("/").contentLength(-1).preExecute(hooks::incrementAndGet).postExecute(hooks::incrementAndGet).build()) {
            assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
            assertEquals(0,hooks.get());
            assertEquals(StageRecord.Status.FAILED,c.record().stage(ExecutionRecord.Stage.VALIDATE).status());
        }
        try (var c = context("/").configure(ExecuteConfigure.builder().temporaryDirectory(temp).curlExecutable(temp.resolve("missing.exe")).build()).build()) {
            c.setPostExecute(hooks::incrementAndGet);
            assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
            assertEquals(1,hooks.get());
            assertEquals(StageRecord.Status.FAILED,c.record().stage(ExecutionRecord.Stage.CURL).status());
        }
    }

    @Test void closeAndExecutionReentryInHooksAreRejected() throws Exception {
        try (var c = context("/").build()) {
            c.setPreExecute(() -> {
                assertThrows(IllegalStateException.class,c::close);
                assertThrows(IllegalStateException.class, () -> new CurlHttpMockExecutor().handle(c));
            });
            new CurlHttpMockExecutor().handle(c);
        }
    }

    @Test void conflictingManagedHeaderFailsWithoutSending() throws Exception {
        try (var c = context("/").requestHeader(HttpHeaderNames.CONTENT_LENGTH,"3").build()) {
            assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
            assertNull(received.get());
        }
    }

    @Test void earlyRejectionKeepsResponseEvenWhenUploadPipeCloses() throws Exception {
        try (var c = context("/reject").contentType(ContentType.APPLICATION_OCTET_STREAM).contentLength(8L*1024*1024).build()) {
            try { new CurlHttpMockExecutor().handle(c); } catch (CurlExecutionException expected) { }
            assertEquals(413,c.record().response().status().orElseThrow().code());
            assertEquals("no",Files.readString(c.record().stdout().orElseThrow().path()));
            assertTrue(c.record().request().stdinBytesWritten() < c.contentLength());
            if (c.record().exitCode().orElseThrow() == 0) {
                assertEquals(ExecutionRecord.State.COMPLETED,c.record().state());
                assertTrue(c.record().response().bodyComplete());
            }
            assertEquals(StageRecord.Status.SKIPPED,c.record().stage(ExecutionRecord.Stage.POST).status());
        }
    }

    @Test void interruptedExecutionRestoresFlagAndReleasesFiles() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        server.createContext("/interrupt",exchange -> {
            try { entered.countDown(); Thread.sleep(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var c = context("/interrupt").build();
        AtomicBoolean interrupted = new AtomicBoolean(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { new CurlHttpMockExecutor().handle(c); }
            catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        worker.start();
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); worker.interrupt(); worker.join(5000);
            assertFalse(worker.isAlive()); assertTrue(interrupted.get());
            assertInstanceOf(CurlExecutionException.class,failure.get());
            Path directory = c.record().stdout().orElseThrow().path().getParent();
            c.close(); assertFalse(Files.exists(directory));
        } finally { worker.interrupt(); worker.join(5000); c.close(); }
    }

    @Test void postFailureDoesNotEraseSuccessfulCurlResult() throws Exception {
        try (var c = context("/").postExecute(() -> { throw new IllegalStateException("post"); }).build()) {
            assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
            assertEquals(StageRecord.Status.SUCCEEDED,c.record().stage(ExecutionRecord.Stage.CURL).status());
            assertTrue(c.record().response().bodyComplete());
            assertEquals(ExecutionRecord.State.FAILED,c.record().state());
        }
    }

    @Test void alreadyInterruptedThreadPreservesInterruptDuringVersionProbeFailure() throws Exception {
        Path executable = System.getProperty("os.name").startsWith("Windows")
            ? Path.of(System.getenv("SystemRoot"),"System32","curl.exe") : Path.of("/usr/bin/curl");
        Assumptions.assumeTrue(Files.isExecutable(executable));
        try (var c = context("/").configure(ExecuteConfigure.builder().curlExecutable(executable).temporaryDirectory(temp).build()).build()) {
            AtomicBoolean restored = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                Thread.currentThread().interrupt();
                try { new CurlHttpMockExecutor().handle(c); } catch (CurlExecutionException expected) { }
                restored.set(Thread.currentThread().isInterrupted());
            });
            worker.start(); worker.join(6000); assertFalse(worker.isAlive()); assertTrue(restored.get());
        }
    }

    @Test void emptyCustomHeaderIsActuallySent() throws Exception {
        try (var c = context("/").requestHeader("X-Empty", "").build()) {
            new CurlHttpMockExecutor().handle(c);
            assertEquals("",receivedHeaders.get().getFirst("X-Empty"));
            assertEquals("",c.record().request().sentHeaders().get(0).get("X-Empty"));
        }
    }

    @Test void colonInRequestTargetDoesNotLoseSentHeaders() throws Exception {
        try (var c = context("/urn:123").requestHeader("X-Test", "present").build()) {
            new CurlHttpMockExecutor().handle(c);
            assertEquals("present",c.record().request().sentHeaders().get(0).get("X-Test"));
        }
    }

    @Test void preCannotBypassFinalHeaderValidationWithNettyBulkCopy() throws Exception {
        try (var c = context("/").build()) {
            c.setPreExecute(() -> c.requestHeaders().add(new DefaultHttpHeaders(false).add("X-One", "ok\r\nX-Injected: yes")));
            assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
            assertNull(received.get());
            assertEquals(StageRecord.Status.FAILED,c.record().stage(ExecutionRecord.Stage.PREPARE).status());
        }
    }
}
