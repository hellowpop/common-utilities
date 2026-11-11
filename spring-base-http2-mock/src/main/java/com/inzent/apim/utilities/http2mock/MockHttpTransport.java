package com.inzent.apim.utilities.http2mock;

import com.inzent.apim.utilities.http.payload.DefaultHttpPayloadGenerator;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

final class MockHttpTransport {
    private final MockExecutionProperties limits;
    private final ScheduledExecutorService deadlines;
    private final HttpClient client;
    private final DefaultHttpPayloadGenerator generator = new DefaultHttpPayloadGenerator();
    MockHttpTransport(MockExecutionProperties limits, ScheduledExecutorService deadlines) {
        this.limits = limits; this.deadlines = deadlines;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(limits.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    record Result(long requestNumber, int threadNumber, long loopNumber, long combinationNumber,
            ExecuteMockRequest.Payload payload, Instant receivedAt, Integer status,
            Map<String,List<String>> headers, boolean success, String body, String bodyEncoding, long bodyBytes,
            boolean bodyTruncated, boolean bodyComplete, String sha256, double elapsedMillis, String error) { }

    static final class Call {
        private final Thread worker = Thread.currentThread();
        private InputStream body;
        private boolean finished;
        volatile boolean aborted, timedOut;
        synchronized void abort(boolean timeout) {
            if (finished) return;
            aborted = true; timedOut |= timeout; worker.interrupt();
            if (body != null) try { body.close(); } catch (IOException ignored) { }
        }
        synchronized void body(InputStream stream) throws IOException {
            body = stream;
            if (aborted) { stream.close(); throw new InterruptedIOException("Request aborted"); }
        }
        synchronized void finish() { finished = true; }
    }

    Result execute(ExecuteMockRequest spec, ExecuteMockRequest.Payload payload, long combinationNumber,
                   long number, int thread, long loop, Call call) {
        long start = System.nanoTime();
        Capture capture = new Capture(limits.responsePreviewBytes());
        var deadline = deadlines.schedule(() -> call.abort(true), limits.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        String error = null;
        try {
            generator.generate(payload.chunked(), payload.contentType(), payload.contentEncoding(), payload.contentLength(), payload.blockSize(), (headers, input) -> {
                try {
                    if (call.aborted) throw new InterruptedIOException("Request aborted");
                    var subscribed = new java.util.concurrent.atomic.AtomicBoolean();
                    var body = HttpRequest.BodyPublishers.ofInputStream(() -> subscribed.compareAndSet(false, true) ? input : null);
                    HttpRequest.BodyPublisher publisher = body;
                    if (!payload.chunked()) {
                        long length = Long.parseLong(headers.get("Content-Length"));
                        publisher = length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.fromPublisher(body, length);
                    }
                    var request = HttpRequest.newBuilder(URI.create(spec.endpointUrl())).timeout(limits.requestTimeout())
                            .header("Content-Type", headers.get("Content-Type"));
                    if (headers.contains("Content-Encoding")) request.header("Content-Encoding", headers.get("Content-Encoding"));
                    spec.headers().forEach(request::header);
                    var response = client.send(request.POST(publisher).build(), HttpResponse.BodyHandlers.ofInputStream());
                    capture.status = response.statusCode(); capture.headers = response.headers().map();
                    try (var responseBody = response.body()) {
                        call.body(responseBody);
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = responseBody.read(buffer)) != -1) {
                            if (call.aborted) throw new InterruptedIOException("Request aborted");
                            capture.accept(buffer, count);
                        }
                        if (call.aborted) throw new InterruptedIOException("Request aborted");
                        capture.complete = true;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new CompletionException(interrupted);
                } catch (IOException failed) { throw new UncheckedIOException(failed); }
            });
        } catch (Exception | LinkageError failure) {
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof UncheckedIOException) && cause.getCause() != null) cause = cause.getCause();
            String detail = cause.getMessage() == null ? "" : cause.getMessage();
            error = cause.getClass().getSimpleName() + ": " + detail.substring(0, Math.min(512, detail.length()));
        } finally {
            call.finish(); deadline.cancel(false);
            if (call.aborted) Thread.interrupted(); // Clear only this call's cancellation before a pooled worker is reused.
        }
        if (call.aborted) error = call.timedOut ? "TIMEOUT" : "CANCELLED";
        byte[] preview = capture.preview.toByteArray();
        String body, encoding;
        try {
            body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(preview)).toString();
            if (body.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')) throw new CharacterCodingException();
            encoding = "UTF-8";
        } catch (CharacterCodingException binary) { body = Base64.getEncoder().encodeToString(preview); encoding = "base64"; }
        return new Result(number, thread, loop, combinationNumber, payload, Instant.now(), capture.status, capture.headers,
                error == null && capture.complete && capture.status != null && capture.status >= 200 && capture.status < 300,
                body, encoding, capture.bytes, capture.bytes > preview.length, capture.complete,
                HexFormat.of().formatHex(capture.digest.digest()), (System.nanoTime() - start) / 1_000_000.0, error);
    }
    private static final class Capture {
        final ByteArrayOutputStream preview = new ByteArrayOutputStream();
        final MessageDigest digest;
        final int limit;
        Integer status;
        Map<String,List<String>> headers = Map.of();
        long bytes;
        boolean complete;
        Capture(int limit) {
            this.limit = limit;
            try { digest = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        void accept(byte[] buffer, int count) {
            digest.update(buffer, 0, count); bytes += count;
            preview.write(buffer, 0, Math.min(count, Math.max(0, limit - preview.size())));
        }
    }
}
