package com.inzent.apim.utilities.netty.http.mockserver;

import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import com.inzent.apim.utilities.netty.http.mockserver.server.NettyHttpMockServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerIntegrationTest {
    NettyHttpMockServer server;
    int port;
    @BeforeEach void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var config = ConfigLoader.load(new String[]{"--server.port="+port,"--server.worker-threads=2",
                "--server.request-timeout-ms=500","--server.max-content-length=1024","--server.max-connections=4"});
        server = new NettyHttpMockServer(config); server.start();
    }
    @AfterEach void stop() { if (server != null) { server.close(); server.close(); } }
    Socket connect() throws IOException { Socket socket = new Socket("127.0.0.1",port); socket.setSoTimeout(3000); return socket; }
    static void send(Socket socket,String wire) throws IOException {
        socket.getOutputStream().write(wire.getBytes(StandardCharsets.ISO_8859_1)); socket.getOutputStream().flush();
    }
    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b; while ((b=in.read()) != -1) { if (b=='\n') break; if (b!='\r') bytes.write(b); }
        if (b == -1 && bytes.size() == 0) throw new EOFException();
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }
    record Reply(String status, Map<String,String> headers, byte[] body) {}
    static Reply response(Socket socket,boolean head) throws IOException {
        InputStream in = socket.getInputStream(); String status=line(in); Map<String,String> headers=new HashMap<>();
        String line; while (!(line=line(in)).isEmpty()) {
            int colon=line.indexOf(':'); headers.put(line.substring(0,colon).toLowerCase(Locale.ROOT),line.substring(colon+1).trim());
        }
        int size=head ? 0 : Integer.parseInt(headers.getOrDefault("content-length","0"));
        byte[] body=in.readNBytes(size); if (body.length != size) throw new EOFException("Truncated response");
        return new Reply(status,headers,body);
    }
    @Test void defaultResponseAndKeepAliveReuse() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            Reply reply=response(socket,false);
            assertEquals("200",reply.status().split(" ")[1]);
            assertEquals("text/plain; charset=UTF-8",reply.headers().get("content-type"));
            String guidance=new String(reply.body(),StandardCharsets.UTF_8);
            assertTrue(guidance.contains("x-response-action"));
            assertTrue(guidance.contains("copy-and-send"));
            assertTrue(guidance.contains("x-status"));
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(response(socket,false).status().contains("200")); assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void explicitOkStillReturnsSimpleResponse() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: ok\r\n\r\n");
            Reply reply=response(socket,false);
            assertEquals("200",reply.status().split(" ")[1]);
            assertEquals("OK",new String(reply.body(),StandardCharsets.UTF_8));
        }
    }
    @Test void defaultHeadUsesGuidanceLengthWithoutSendingBody() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            Reply guidance=response(socket,false);
            send(socket,"HEAD / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            Reply head=response(socket,true);
            assertEquals("200",head.status().split(" ")[1]);
            assertEquals(Integer.toString(guidance.body().length),head.headers().get("content-length"));
            assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void customActionHeaderUsesGuidanceByDefaultAndSelectsExplicitOk() throws Exception {
        server.close();
        var config=ConfigLoader.load(new String[]{"--server.port="+port,"--server.worker-threads=1","--mock.header-name=x-mock-action"});
        server=new NettyHttpMockServer(config); server.start();
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            Reply reply=response(socket,false);
            assertEquals("200",reply.status().split(" ")[1]);
            assertTrue(new String(reply.body(),StandardCharsets.UTF_8).contains("copy-and-send"));
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-mock-action: ok\r\n\r\n");
            assertEquals("OK",new String(response(socket,false).body(),StandardCharsets.UTF_8));
        }
    }
    @Test void explicitlyConfiguredDefaultActionOverridesGuidanceFallback() throws Exception {
        server.close();
        var config=ConfigLoader.load(new String[]{"--server.port="+port,"--server.worker-threads=1","--mock.default-action=ok"});
        server=new NettyHttpMockServer(config); server.start();
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            Reply reply=response(socket,false);
            assertEquals("200",reply.status().split(" ")[1]);
            assertEquals("OK",new String(reply.body(),StandardCharsets.UTF_8));
        }
    }
    @Test void statusHeaderSelectsResponseCodeAndPreservesMessageBoundaries() throws Exception {
        try (Socket socket=connect()) {
            for (int code:new int[]{200,201,204,205,304,404,429,500,599}) {
                send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: status\r\nX-StAtUs: "+code+"\r\n\r\n");
                Reply reply=response(socket,false);
                assertEquals(Integer.toString(code),reply.status().split(" ")[1]);
                assertEquals(0,reply.body().length);
                if (code==204 || code==304) assertFalse(reply.headers().containsKey("content-length"));
                else assertEquals("0",reply.headers().get("content-length"));
                assertFalse(reply.headers().containsKey("transfer-encoding"));
            }
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: ok\r\n\r\n");
            assertEquals("OK",new String(response(socket,false).body(),StandardCharsets.UTF_8));
        }
    }
    @Test void statusHeadReturnsSelectedCodeThenCloses() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"HEAD / HTTP/1.1\r\nHost: localhost\r\nx-response-action: status\r\nx-status: 451\r\nConnection: close\r\n\r\n");
            Reply reply=response(socket,true);
            assertEquals("451",reply.status().split(" ")[1]);
            assertEquals("0",reply.headers().get("content-length"));
            assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void rejectsMissingDuplicateOrInvalidStatusHeader() throws Exception {
        for (String headers:List.of("", "x-status: \r\n", "x-status: 100\r\n", "x-status: 199\r\n",
                "x-status: 600\r\n", "x-status: -1\r\n", "x-status: +200\r\n", "x-status: 200.0\r\n",
                "x-status: 0200\r\n", "x-status: 200,201\r\n", "x-status: 999999999999999\r\n",
                "x-status: 200\r\nx-status: 201\r\n")) {
            try (Socket socket=connect()) {
                send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: status\r\n"+headers+"\r\n");
                Reply reply=response(socket,false);
                assertEquals("400",reply.status().split(" ")[1]);
                assertEquals("INVALID_STATUS\n",new String(reply.body(),StandardCharsets.UTF_8));
                assertEquals(-1,socket.getInputStream().read());
            }
        }
    }
    @Test void otherActionsIgnoreStatusHeader() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: ok\r\nx-status: invalid\r\n\r\n");
            Reply reply=response(socket,false);
            assertEquals("200",reply.status().split(" ")[1]);
            assertEquals("OK",new String(reply.body(),StandardCharsets.UTF_8));
        }
    }
    @Test void copyAndHead() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"POST / HTTP/1.1\r\nHost: localhost\r\nx-response-action: copy-and-send\r\nContent-Length: 3\r\n\r\nabc");
            assertArrayEquals(new byte[]{97,98,99},response(socket,false).body());
            send(socket,"HEAD / HTTP/1.1\r\nHost: localhost\r\nx-response-action: ok\r\nConnection: close\r\n\r\n");
            assertEquals("2",response(socket,true).headers().get("content-length"));
            assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void fakeCloseKeepsHeaderThenClosesAfterCompleteBody() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: fake-close\r\nConnection: close\r\n\r\n"
                    +"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            Reply reply=response(socket,false);
            assertEquals("keep-alive",reply.headers().get("connection"));
            assertEquals("OK",new String(reply.body(),StandardCharsets.UTF_8));
            assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void closeHasNoResponse() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: close\r\n\r\n");
            assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void fakeCloseHeadSendsOnlyHeadersThenEof() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"HEAD / HTTP/1.1\r\nHost: localhost\r\nx-response-action: fake-close\r\n\r\n");
            Reply reply=response(socket,true);
            assertEquals("keep-alive",reply.headers().get("connection"));
            assertEquals("2",reply.headers().get("content-length"));
            assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void delayedCloseSurvivesRequestTimeoutWithoutBlockingOthers() throws Exception {
        try (Socket delayed=connect()) {
            long begin=System.nanoTime();
            send(delayed,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: delay-close\r\nx-delay-amount: 900\r\n\r\n"
                    +"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            try (Socket other=connect()) {
                send(other,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertTrue(response(other,false).status().contains("200"));
            }
            assertEquals(-1,delayed.getInputStream().read());
            long elapsed=(System.nanoTime()-begin)/1_000_000;
            assertTrue(elapsed>=850,"Closed prematurely at "+elapsed+"ms");
            assertTrue(elapsed<2800,"Close was excessively delayed");
        }
    }
    @Test void zeroDelayAndInvalidDelayAreDistinct() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: delay-close\r\nx-delay-amount: 0\r\n\r\n");
            assertEquals(-1,socket.getInputStream().read());
        }
        for (String extra : List.of("", "x-delay-amount: -1\r\n", "x-delay-amount: 1\r\nx-delay-amount: 2\r\n")) {
            try (Socket socket=connect()) {
                send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: delay-close\r\n"+extra+"\r\n");
                Reply reply=response(socket,false); assertTrue(reply.status().contains("400"));
                assertEquals("INVALID_DELAY_AMOUNT\n",new String(reply.body(),StandardCharsets.UTF_8));
            }
        }
    }
    @Test void invalidActionsAndHttpVersion() throws Exception {
        for (String headers : List.of("x-response-action: missing\r\n", "x-response-action: \r\n", "x-response-action: ok\r\nx-response-action: close\r\n")) {
            try (Socket socket=connect()) {
                send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n"+headers+"\r\n");
                assertTrue(response(socket,false).status().contains("400"));
            }
        }
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.0\r\n\r\n"); assertTrue(response(socket,false).status().contains("505"));
        }
    }
    @Test void legacyCopyHeaderIsNotAnAliasForProcessorResponseType() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: copy\r\n\r\n");
            Reply reply=response(socket,false);
            assertTrue(reply.status().contains("400"));
            assertEquals("UNKNOWN_ACTION\n",new String(reply.body(),StandardCharsets.UTF_8));
        }
    }
    @Test void oversizedBodyAndIncompleteRequest() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1025\r\nExpect: 100-continue\r\n\r\n");
            assertTrue(response(socket,false).status().contains("413"));
            assertEquals(-1,socket.getInputStream().read());
        }
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost:"); assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void shutdownDoesNotWaitForDelayedClose() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: delay-close\r\nx-delay-amount: 60000\r\n\r\n");
            server.close(); assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test void maxConnectionsRejectsExcessAndReleasesAdmission() throws Exception {
        List<Socket> sockets=new ArrayList<>();
        try {
            for (int i=0;i<4;i++) {
                Socket socket=connect(); sockets.add(socket);
                send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertTrue(response(socket,false).status().contains("200"));
            }
            try (Socket excess=connect()) { assertEquals(-1,excess.getInputStream().read()); }
        } finally { for (Socket socket:sockets) socket.close(); }
    }
    @Test void expectContinueAndMalformedFraming() throws Exception {
        try (Socket socket=connect()) {
            send(socket,"POST / HTTP/1.1\r\nHost: localhost\r\nx-response-action: copy-and-send\r\nContent-Length: 3\r\nExpect: 100-continue\r\n\r\n");
            assertTrue(response(socket,false).status().contains("100"));
            send(socket,"abc"); assertArrayEquals(new byte[]{97,98,99},response(socket,false).body());
        }
        for (String framing:List.of("Content-Length: 1\r\nContent-Length: 2\r\n", "Content-Length: nope\r\n")) {
            try (Socket socket=connect()) {
                send(socket,"POST / HTTP/1.1\r\nHost: localhost\r\n"+framing+"\r\nx");
                assertTrue(response(socket,false).status().contains("400"));
            }
        }
    }
    @Test void peerDisconnectDuringLongDelayReleasesConnectionAdmission() throws Exception {
        server.close();
        var config=ConfigLoader.load(new String[]{"--server.port="+port,"--server.worker-threads=1","--server.max-connections=1"});
        server=new NettyHttpMockServer(config); server.start();
        try (Socket delayed=connect()) {
            send(delayed,"GET / HTTP/1.1\r\nHost: localhost\r\nx-response-action: delay-close\r\nx-delay-amount: 60000\r\n\r\n");
            // A quiet connection confirms the action is waiting rather than returning a response.
            delayed.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class,() -> delayed.getInputStream().read());
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        boolean admitted=false;
        while (System.nanoTime()<deadline) {
            try (Socket next=connect()) {
                send(next,"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                if (response(next,false).status().contains("200")) { admitted=true; break; }
            } catch (IOException ignored) { Thread.sleep(20); }
        }
        assertTrue(admitted,"Disconnected delay-close client retained connection admission");
    }
}
