package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.lang.management.ManagementFactory;



class HttpRequestDecoderTest {
    static HttpProtocolResult feed(HttpRequestDecoder decoder, String text) throws IOException {
        return decoder.feed(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test void stagesAdvanceOnlyWhenBytesArriveAndSnapshotsDoNotChange() throws Exception {
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        Assertions.assertEquals(HttpReceiveState.NOT_REACHED, d.result().request().initialLineState());
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot first = feed(d, "PO").request();
        Assertions.assertEquals(HttpReceiveState.RECEIVING, first.initialLineState());
        Assertions.assertEquals(HttpReceiveState.NOT_REACHED, first.headerState());
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot line = feed(d, "ST /x HTTP/1.1\r\n").request();
        Assertions.assertEquals(HttpReceiveState.COMPLETE, line.initialLineState());
        Assertions.assertEquals(HttpReceiveState.NOT_REACHED, line.headerState());
        Assertions.assertEquals(HttpReceiveState.RECEIVING, feed(d, "Ho").request().headerState());
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot headers = feed(d, "st: local\r\nContent-Length: 3\r\n\r\n").request();
        Assertions.assertEquals(HttpReceiveState.COMPLETE, headers.headerState());
        Assertions.assertEquals(HttpReceiveState.NOT_REACHED, headers.contentState());
        Assertions.assertEquals(HttpReceiveState.NOT_APPLICABLE, headers.trailersState());
        Assertions.assertEquals(HttpReceiveState.RECEIVING, feed(d, "a").request().contentState());
        com.inzent.apim.utilities.http.mockserver.HttpProtocolResult done = feed(d, "bc");
        Assertions.assertTrue(done.request().complete());
        Assertions.assertEquals(HttpReceiveState.COMPLETE, done.request().contentState());
        Assertions.assertEquals(1, done.completedRequests().size());
        Assertions.assertArrayEquals(new byte[]{97,98,99}, done.request().content());
        Assertions.assertEquals(HttpReceiveState.RECEIVING, first.initialLineState());
        Assertions.assertArrayEquals(new byte[0], headers.content());
    }

    @Test void zeroOrMissingContentLengthMarksContentAndTrailersNotApplicable() throws Exception {
        for (String header : List.of("", "Content-Length: 0\r\n")) {
            com.inzent.apim.utilities.http.mockserver.HttpProtocolResult result = feed(new HttpRequestDecoder(HttpProtocolLimits.defaults()),
                "GET / HTTP/1.1\r\nHost: local\r\n" + header + "\r\n");
            Assertions.assertEquals(HttpReceiveState.NOT_APPLICABLE, result.request().contentState());
            Assertions.assertEquals(HttpReceiveState.NOT_APPLICABLE, result.request().trailersState());
            Assertions.assertTrue(result.request().complete());
        }
    }

    @Test void chunkedContentCompletesBeforeTrailersAndExtensionsAreConsumed() throws Exception {
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        feed(d, "POST / HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n");
        Assertions.assertEquals(HttpReceiveState.RECEIVING, feed(d, "3;x=\"a;b\"\r\na").request().contentState());
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot content = feed(d, "bc\r\n0\r\n").request();
        Assertions.assertEquals(HttpReceiveState.COMPLETE, content.contentState());
        Assertions.assertEquals(HttpReceiveState.NOT_REACHED, content.trailersState());
        Assertions.assertFalse(content.complete());
        Assertions.assertEquals(HttpReceiveState.RECEIVING, feed(d, "X-Check: y\r\n").request().trailersState());
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot done = feed(d, "\r\n").request();
        Assertions.assertEquals(HttpReceiveState.COMPLETE, done.trailersState());
        Assertions.assertEquals(List.of("y"), done.trailers().get("x-check"));
        Assertions.assertArrayEquals(new byte[]{97,98,99}, done.content());
        Assertions.assertTrue(done.complete());
    }

    @Test void everyPossibleSplitAndSingleByteFeedProduceSameBinaryContent() throws Exception {
        String wire = "POST / HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n3\r\n\u0000\u00ffA\r\n0\r\n\r\n";
        byte[] bytes = wire.getBytes(StandardCharsets.ISO_8859_1);
        for (int split = 0; split <= bytes.length; split++) {
            com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
            d.feed(ByteBuffer.wrap(bytes, 0, split));
            com.inzent.apim.utilities.http.mockserver.HttpProtocolResult result = d.feed(ByteBuffer.wrap(bytes, split, bytes.length - split));
            Assertions.assertTrue(result.request().complete(), "split=" + split);
            Assertions.assertArrayEquals(new byte[]{0, (byte)255, 65}, result.request().content());
        }
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        for (byte b : bytes) d.feed(ByteBuffer.wrap(new byte[]{b}));
        Assertions.assertTrue(d.result().request().complete());
    }

    @Test void allPipelinedBytesAreParsedIncludingPartialNextRequest() throws Exception {
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        com.inzent.apim.utilities.http.mockserver.HttpProtocolResult result = feed(d, "GET /a HTTP/1.1\r\nHost: local\r\n\r\nGET /b HTTP/1.0\r\n\r\nPO");
        Assertions.assertEquals(2, result.completedRequests().size());
        Assertions.assertEquals("GET /a HTTP/1.1", result.completedRequests().get(0).initialLine());
        Assertions.assertEquals("GET /b HTTP/1.0", result.completedRequests().get(1).initialLine());
        Assertions.assertEquals(HttpReceiveState.RECEIVING, result.request().initialLineState());
        Assertions.assertFalse(result.request().complete());
        Assertions.assertTrue(d.endOfInput().truncated());
    }

    @Test void partialEofPreservesIncompleteStateWhileCleanEofIsNotTruncated() throws Exception {
        for (String wire : List.of("G", "GET / HTTP/1.1\r\nHo",
            "POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 3\r\n\r\na",
            "POST / HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX: a\r\n")) {
            com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
            feed(d, wire);
            Assertions.assertTrue(d.endOfInput().truncated());
            Assertions.assertTrue(d.result().endOfInput());
            Assertions.assertThrows(IllegalStateException.class, () -> feed(d, "more"));
        }
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder empty = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        Assertions.assertFalse(empty.endOfInput().truncated());
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder done = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        feed(done, "GET / HTTP/1.0\r\n\r\n");
        Assertions.assertFalse(done.endOfInput().truncated());
    }

    @Test void duplicateFieldsAndIdenticalLengthsArePreservedAndDefensivelyCopied() throws Exception {
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot request = feed(d, "POST / HTTP/1.1\r\nHost: local\r\nX: a\r\nx: b\r\nContent-Length: 1, 1\r\nContent-Length: 1\r\n\r\nz").request();
        Assertions.assertEquals(List.of("a", "b"), request.headers().get("x"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> request.headers().put("x", List.of()));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> request.headers().get("x").clear());
        byte[] content = request.content();
        content[0] = 0;
        Assertions.assertArrayEquals(new byte[]{122}, request.content());
    }

    @Test void ambiguousAndInvalidFramingIsRejectedAndDecoderCannotResume() {
        for (String headers : List.of("Content-Length: 1\r\nContent-Length: 2\r\n",
            "Content-Length: -1\r\n", "Content-Length: +1\r\n", "Content-Length: 99999999999999999999999999\r\n",
            "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n", "Transfer-Encoding: gzip, chunked\r\n",
            "Transfer-Encoding: chunked, chunked\r\n", "Bad : field\r\n", " folded\r\n")) {
            com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
            Assertions.assertThrows(IOException.class, () -> feed(d, "POST / HTTP/1.1\r\nHost: local\r\n" + headers + "\r\n"), headers);
            Assertions.assertThrows(IllegalStateException.class, () -> feed(d, "GET / HTTP/1.0\r\n\r\n"));
        }
    }

    @Test void malformedLinesChunksAndFramingTrailersAreRejected() {
        for (String wire : List.of("GET / HTTP/1.1\n", "GET / HTTP/1.1\rx", "BAD\r\n",
            "GET / HTTP/2.0\r\n", "GET / HTTP/1.1\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")) {
            Assertions.assertThrows(IOException.class, () -> feed(new HttpRequestDecoder(HttpProtocolLimits.defaults()), wire));
        }
        String head = "POST / HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n";
        for (String tail : List.of("-1\r\n", "g\r\n", "FFFFFFFFFFFFFFFFFFFFFFFF\r\n", "1\r\naX",
            "1;bad=\"unterminated\r\n", "0\r\nContent-Length: 1\r\n\r\n")) {
            Assertions.assertThrows(IOException.class, () -> feed(new HttpRequestDecoder(HttpProtocolLimits.defaults()), head + tail), tail);
        }
    }

    @Test void limitsRejectOversizedLineHeadersFixedAndChunkedContent() throws Exception {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new HttpProtocolLimits(0, 20, 3));
        Assertions.assertThrows(IOException.class, () -> feed(new HttpRequestDecoder(new HttpProtocolLimits(3, 100, 3)), "GET "));
        Assertions.assertThrows(IOException.class, () -> feed(new HttpRequestDecoder(new HttpProtocolLimits(100, 8, 3)),
            "GET / HTTP/1.1\r\nHost: local\r\n\r\n"));
        for (String framing : List.of("Content-Length: 4\r\n\r\n", "Transfer-Encoding: chunked\r\n\r\n4\r\n")) {
            Assertions.assertThrows(IOException.class, () -> feed(new HttpRequestDecoder(new HttpProtocolLimits(100, 100, 3)),
                "POST / HTTP/1.1\r\nHost: local\r\n" + framing));
        }
    }

    @Test void fragmentedBodiesDoNotCopyAllPreviouslyReceivedContentOnEveryRead() throws Exception {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        feed(d, "POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 524288\r\n\r\n");
        byte[] bytes = new byte[1024];
        java.util.Arrays.fill(bytes, (byte) 65);
        HttpRequestSnapshot earlier = null;
        long threadId = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < 512; i++) {
            com.inzent.apim.utilities.http.mockserver.HttpProtocolResult result = d.feed(ByteBuffer.wrap(bytes));
            if (i == 0) earlier = result.request();
        }
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;
        Assertions.assertTrue(allocated < 64L * 1024 * 1024, "fragmented-read allocations=" + allocated);
        Assertions.assertNotNull(earlier);
        Assertions.assertEquals(1024, earlier.content().length);
        Assertions.assertEquals(HttpReceiveState.RECEIVING, earlier.contentState());
        Assertions.assertTrue(d.result().request().complete());
        byte[] full = d.result().request().content();
        Assertions.assertEquals(524288, full.length);
        Assertions.assertTrue(java.util.Arrays.equals(new byte[]{65,65}, new byte[]{full[0], full[full.length - 1]}));
    }

    @Test void largeHeadersAreNotRecopiedForEveryBodyFragment() throws Exception {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        java.lang.StringBuilder header = new StringBuilder("POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 262144\r\n");
        for (int i = 0; i < 4000; i++) header.append("X-").append(i).append(": a\r\n");
        header.append("\r\n");
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        feed(d, header.toString());
        long threadId = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(threadId);
        byte[] bytes = new byte[1024];
        for (int i = 0; i < 256; i++) d.feed(ByteBuffer.wrap(bytes));
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;
        Assertions.assertTrue(allocated < 64L * 1024 * 1024, "header-copy allocations=" + allocated);
        Assertions.assertEquals(List.of("a"), d.result().request().headers().get("x-3999"));
        Assertions.assertTrue(d.result().request().complete());
    }

    @Test void sharedContentBlocksKeepOrderAndEarlierPartialViewsStayUnchanged() throws Exception {
        byte[] input = new byte[20000];
        for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 31);
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder d = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        feed(d, "POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 20000\r\n\r\n");
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot earlier = d.feed(ByteBuffer.wrap(input, 0, 9000)).request();
        d.feed(ByteBuffer.wrap(input, 9000, 11000));
        Assertions.assertArrayEquals(input, d.result().request().content());
        Assertions.assertArrayEquals(java.util.Arrays.copyOf(input, 9000), earlier.content());
        Assertions.assertEquals(9000, earlier.contentBytes());
    }
}
