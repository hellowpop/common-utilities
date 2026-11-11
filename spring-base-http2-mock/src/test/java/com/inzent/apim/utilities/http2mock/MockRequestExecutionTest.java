package com.inzent.apim.utilities.http2mock;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = MockApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock.http.port=0", "mock.services.netty.enabled=false", "mock.services.response.enabled=false",
                "mock.execution.max-concurrent-jobs=1", "mock.execution.max-thread-count=4", "mock.execution.response-preview-bytes=64",
                "mock.execution.max-summary-groups=2", "mock.execution.request-timeout=500ms", "mock.execution.job-timeout=2s",
                "mock.execution.heartbeat-interval=100ms"})
class MockRequestExecutionTest {
    @Autowired ServletWebServerApplicationContext context;
    @Autowired MockRequestExecutionService executions;
    final ObjectMapper json = new ObjectMapper();
    HttpServer target;
    ExecutorService targetThreads;
    final ConcurrentLinkedQueue<Captured> captured = new ConcurrentLinkedQueue<>();
    final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), sequence = new AtomicInteger();
    record Captured(String method, Map<String,List<String>> headers, byte[] body) { }
    record Event(String name, JsonNode data) { }

    @BeforeEach void startTarget() throws Exception {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        targetThreads = Executors.newCachedThreadPool(); target.setExecutor(targetThreads);
        target.createContext("/", exchange -> {
            int concurrent = active.incrementAndGet(); maximum.accumulateAndGet(concurrent, Math::max);
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                captured.add(new Captured(exchange.getRequestMethod(), Map.copyOf(exchange.getRequestHeaders()), body));
                int number = sequence.incrementAndGet();
                try { Thread.sleep(40); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return; }
                byte[] reply = ("reply-" + number % 2).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(number % 2 == 0 ? 503 : 200, reply.length);
                exchange.getResponseBody().write(reply);
            } finally { active.decrementAndGet(); exchange.close(); }
        });
        target.start();
    }
    @AfterEach void stopTarget() {
        target.stop(0); targetThreads.shutdownNow();
    }
    String endpoint() { return "http://127.0.0.1:" + target.getAddress().getPort() + "/"; }
    URI executeUri() {
        int port = Arrays.stream(((TomcatWebServer) context.getWebServer()).getTomcat().getService().findConnectors())
                .filter(c -> !c.getSecure()).findFirst().orElseThrow().getLocalPort();
        return URI.create("http://127.0.0.1:" + port + "/api/execute-mock-request");
    }
    Map<String,Object> specification(boolean chunked, String encoding) {
        var spec = new LinkedHashMap<String,Object>();
        spec.put("threadCount", 3); spec.put("loopCount", 2); spec.put("endpointUrl", endpoint());
        spec.put("chunked", List.of(chunked)); spec.put("contentType", List.of("APPLICATION_JSON")); spec.put("contentEncoding", List.of(encoding));
        spec.put("contentLength", List.of(128)); spec.put("blockSize", List.of(8)); spec.put("headers", Map.of("X-Test", "execution"));
        return spec;
    }
    HttpRequest request(Map<String,Object> spec) throws Exception {
        return HttpRequest.newBuilder(executeUri()).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(spec))).build();
    }
    List<Event> events(String body) throws Exception {
        var events = new ArrayList<Event>(); String name = "";
        for (String line : body.lines().toList()) {
            if (line.startsWith("event:")) name = line.substring(6).trim();
            if (line.startsWith("data:")) events.add(new Event(name, json.readTree(line.substring(5))));
        }
        return events;
    }
    @Test void sendsOnlyEndpointAndStatusAndOmitsResponseSummaries() throws Exception {
        var spec = specification(false, "IDENTITY");
        var received = events(HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString()).body());
        var results = received.stream().filter(e -> e.name().equals("response")).map(Event::data).toList();
        assertThat(results).hasSize(6);
        for (var result : results) {
            assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("endpointUrl", "status");
            assertThat(result.path("endpointUrl").asText()).isEqualTo(endpoint());
            assertThat(result.path("status").asInt()).isIn(200, 503);
        }
        var summary = received.get(received.size()-1);
        assertThat(summary.name()).isEqualTo("summary");
        assertThat(summary.data().has("responseSummaries")).isFalse();
        assertThat(summary.data().path("completedCount").asLong()).isEqualTo(6);
    }

    @Test void rejectsInvalidSpecificationBeforeStartingBackgroundWork() throws Exception {
        for (var change : List.of(Map.<String,Object>of("threadCount", 0), Map.<String,Object>of("loopCount", Long.MAX_VALUE),
                Map.<String,Object>of("contentLength", List.of(1)), Map.<String,Object>of("blockSize", List.of(0)),
                Map.<String,Object>of("endpointUrl", "file:///tmp/not-http"), Map.<String,Object>of("headers", Map.of("Content-Length", "1")),
                Map.<String,Object>of("contentEncoding", List.of("UNKNOWN")))) {
            var invalid = specification(false, "IDENTITY"); invalid.putAll(change);
            var response = HttpClient.newHttpClient().send(request(invalid), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).has("error")).isTrue();
        }
        assertThat(executions.activeJobCount()).isZero(); assertThat(captured).isEmpty();
    }

    @Test void executesCartesianProductForEveryThreadAndLoop() throws Exception {
        target.createContext("/matrix", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured.add(new Captured(exchange.getRequestMethod(), Map.copyOf(exchange.getRequestHeaders()), body));
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(200, 1);
            try { exchange.getResponseBody().write(1); } finally { exchange.close(); }
        });
        var spec = specification(false, "IDENTITY");
        spec.put("endpointUrl", endpoint()+"matrix"); spec.put("threadCount", 2); spec.put("loopCount", 2);
        spec.put("chunked", List.of(false, true));
        spec.put("contentType", List.of("TEXT_PLAIN", "APPLICATION_JSON"));
        spec.put("contentEncoding", List.of("IDENTITY", "GZIP"));
        spec.put("contentLength", List.of(128, 256)); spec.put("blockSize", List.of(8, 16));
        var response = HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var received = events(response.body());
        assertThat(received.get(0).data().path("combinationCount").asLong()).isEqualTo(32);
        var results = received.stream().filter(e -> e.name().equals("response")).map(Event::data).toList();
        assertThat(results).hasSize(128);
        for (var result : results) {
            assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("endpointUrl", "status");
            assertThat(result.path("endpointUrl").asText()).isEqualTo(endpoint()+"matrix");
            assertThat(result.path("status").asInt()).isEqualTo(200);
        }
        assertThat(captured).hasSize(128);
        var wireCases = new HashMap<String,Integer>();
        for (var actual : captured) {
            var headers = new TreeMap<String,List<String>>(String.CASE_INSENSITIVE_ORDER); headers.putAll(actual.headers());
            boolean gzip = headers.getOrDefault("Content-Encoding", List.of("")).contains("gzip");
            boolean chunked = headers.containsKey("Transfer-Encoding");
            byte[] decoded = gzip ? new GZIPInputStream(new ByteArrayInputStream(actual.body())).readAllBytes() : actual.body();
            if (chunked) assertThat(headers).doesNotContainKey("Content-Length");
            else assertThat(headers.get("Content-Length")).containsExactly(String.valueOf(actual.body().length));
            if (headers.get("Content-Type").get(0).startsWith("application/json")) assertThat(json.readTree(decoded).isObject()).isTrue();
            wireCases.merge(chunked+":"+gzip+":"+headers.get("Content-Type")+":"+decoded.length, 1, Integer::sum);
        }
        assertThat(wireCases).hasSize(16); assertThat(wireCases.values()).containsOnly(8);
        var summary = received.get(received.size()-1);
        assertThat(summary.name()).isEqualTo("summary");
        assertThat(summary.data().path("totalCount").asLong()).isEqualTo(128);
        assertThat(summary.data().path("successCount").asLong()).isEqualTo(128);
        assertThat(summary.data().path("remainingCount").asLong()).isZero();
    }

    @Test void rejectsInvalidArraysAndOversizedCartesianProductBeforeWork() throws Exception {
        for (var value : List.of(List.of(), Arrays.asList(true, null), true)) {
            var spec = specification(false, "IDENTITY"); spec.put("chunked", value);
            assertThat(HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        }
        var spec = specification(false, "IDENTITY");
        spec.put("chunked", List.of(true, false)); spec.put("loopCount", 20000);
        assertThat(HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        spec.put("loopCount", Long.MAX_VALUE);
        assertThat(HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        spec.put("loopCount", 1); spec.put("contentType", List.of("TEXT_PLAIN", "APPLICATION_JSON"));
        spec.put("contentLength", List.of(1, 128));
        assertThat(HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(captured).isEmpty(); assertThat(executions.activeJobCount()).isZero();
    }

    @Test void drainsBinaryResponseAndSendsOnlyStatus() throws Exception {
        byte[] binary = new byte[1024]; Arrays.fill(binary, (byte) 0xff);
        target.createContext("/binary", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, binary.length);
            try { exchange.getResponseBody().write(binary); } finally { exchange.close(); }
        });
        var spec = specification(false, "IDENTITY"); spec.put("endpointUrl", endpoint() + "binary"); spec.put("threadCount", 1); spec.put("loopCount", 1);
        var received = events(HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString()).body());
        var result = received.stream().filter(e -> e.name().equals("response")).findFirst().orElseThrow().data();
        assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("endpointUrl", "status");
        assertThat(result.path("endpointUrl").asText()).isEqualTo(endpoint()+"binary");
        assertThat(result.path("status").asInt()).isEqualTo(200);
        var summary = received.get(received.size()-1).data();
        assertThat(summary.path("totalResponseBytes").asLong()).isEqualTo(1024);
        assertThat(summary.path("successCount").asLong()).isEqualTo(1);
        assertThat(summary.has("responseSummaries")).isFalse();
    }

    @Test void bodyReadTimeoutIsReportedAndStillEndsWithSummary() throws Exception {
        target.createContext("/slow-body", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 100);
            try {
                exchange.getResponseBody().write(new byte[]{1,2,3}); exchange.getResponseBody().flush();
                Thread.sleep(1200); exchange.getResponseBody().write(new byte[97]);
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            catch (IOException disconnected) { }
            finally { exchange.close(); }
        });
        var spec = specification(false, "IDENTITY"); spec.put("endpointUrl", endpoint() + "slow-body"); spec.put("threadCount", 1); spec.put("loopCount", 1);
        var received = events(HttpClient.newHttpClient().send(request(spec), HttpResponse.BodyHandlers.ofString()).body());
        var result = received.stream().filter(e -> e.name().equals("response")).findFirst().orElseThrow().data();
        assertThat(result.path("status").asInt()).isEqualTo(200);
        assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("endpointUrl", "status");
        var summary = received.get(received.size()-1).data();
        assertThat(summary.path("failureCount").asLong()).isEqualTo(1);
        assertThat(summary.path("errorCounts").path("TIMEOUT").asLong()).isEqualTo(1);
    }

    @Test void networkFailuresCountPerAttemptAndResponseGroupsAreBounded() throws Exception {
        int closedPort; try (var socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        var failed = specification(false, "IDENTITY"); failed.put("endpointUrl", "http://127.0.0.1:" + closedPort + "/");
        var failures = events(HttpClient.newHttpClient().send(request(failed), HttpResponse.BodyHandlers.ofString()).body());
        assertThat(failures.get(failures.size()-1).data().path("completedCount").asLong()).isEqualTo(6);
        assertThat(failures.get(failures.size()-1).data().path("failureCount").asLong()).isEqualTo(6);
        for (var event : failures) if (event.name().equals("response")) {
            assertThat(event.data().properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("endpointUrl", "status");
            assertThat(event.data().path("status").isNull()).isTrue();
            assertThat(event.data().path("endpointUrl").asText()).isEqualTo(failed.get("endpointUrl"));
        }
        target.createContext("/unique", exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] bytes = ("unique-"+sequence.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        var unique = specification(false,"IDENTITY"); unique.put("endpointUrl",endpoint()+"unique"); unique.put("threadCount",1); unique.put("loopCount",4);
        var received = events(HttpClient.newHttpClient().send(request(unique),HttpResponse.BodyHandlers.ofString()).body());
        var summary=received.get(received.size()-1).data();
        assertThat(summary.has("responseSummaries")).isFalse();
        assertThat(summary.path("ungroupedResponseCount").asLong()).isEqualTo(2);
    }

    @Test void limitsConcurrentJobsAndCancelsWhenTheSseClientDisconnects() throws Exception {
        var spec=specification(false,"IDENTITY"); spec.put("threadCount",1); spec.put("loopCount",1000);
        var client=HttpClient.newHttpClient();
        var first=client.send(request(spec),HttpResponse.BodyHandlers.ofInputStream());
        try (var body=first.body()) {
            var reader=new BufferedReader(new InputStreamReader(body,StandardCharsets.UTF_8));
            String line; while ((line=reader.readLine())!=null && !line.startsWith("data:")) { }
            assertThat(line).isNotNull();
            var rejected=client.send(request(spec),HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(429);
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(executions.activeJobCount()!=0 && System.nanoTime()<deadline) Thread.sleep(25);
        assertThat(executions.activeJobCount()).isZero();
        int count=captured.size(); Thread.sleep(150); assertThat(captured).hasSize(count);
    }

    @Test void jobTimeoutSendsPartialSummaryAndClosesTheStream() throws Exception {
        var spec=specification(false,"IDENTITY"); spec.put("threadCount",1); spec.put("loopCount",1000);
        var received=events(HttpClient.newHttpClient().send(request(spec),HttpResponse.BodyHandlers.ofString()).body());
        var summary=received.get(received.size()-1);
        assertThat(summary.name()).isEqualTo("summary");
        assertThat(summary.data().path("cancelled").asBoolean()).isTrue();
        assertThat(summary.data().path("cancellationReason").asText()).isEqualTo("JOB_TIMEOUT");
        assertThat(summary.data().path("completedCount").asLong()).isLessThan(1000);
    }

    @Test void unreadSseAppliesBackpressureButCancelsWorkersAtJobTimeout() throws Exception {
        var requests = new AtomicInteger();
        target.createContext("/large-headers", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("X-Padding", "a".repeat(16000));
            exchange.sendResponseHeaders(200, 1);
            try { exchange.getResponseBody().write(1); } finally { exchange.close(); }
        });
        var spec = specification(false, "IDENTITY"); spec.put("endpointUrl", endpoint()+"large-headers");
        spec.put("threadCount",4); spec.put("loopCount",1000);
        try (var stalled = new Socket()) {
            stalled.setReceiveBufferSize(1024); stalled.connect(new InetSocketAddress(executeUri().getHost(), executeUri().getPort()));
            byte[] body = json.writeValueAsBytes(spec);
            String header = "POST /api/execute-mock-request HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
            stalled.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII)); stalled.getOutputStream().write(body);
            Thread.sleep(2400);
            int count = requests.get(); assertThat(count).isPositive();
            Thread.sleep(200); assertThat(requests).hasValue(count);
        }
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (executions.activeJobCount()!=0 && System.nanoTime()<deadline) Thread.sleep(25);
        assertThat(executions.activeJobCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"false IDENTITY", "true IDENTITY", "false GZIP", "true GZIP"})
    void executesPerThreadLoopsWithRealPayloadFramingAndSseSummary(String mode) throws Exception {
        String[] parts = mode.split(" "); boolean chunked = Boolean.parseBoolean(parts[0]);
        var response = HttpClient.newHttpClient().send(request(specification(chunked, parts[1])), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        var received = events(response.body());
        assertThat(received.get(0).name()).isEqualTo("started");
        assertThat(received.stream().filter(e -> e.name().equals("response"))).hasSize(6);
        var summary = received.get(received.size() - 1);
        assertThat(summary.name()).isEqualTo("summary");
        assertThat(summary.data().path("totalCount").asLong()).isEqualTo(6);
        assertThat(summary.data().path("completedCount").asLong()).isEqualTo(6);
        assertThat(summary.data().path("successCount").asLong()).isEqualTo(3);
        assertThat(summary.data().path("failureCount").asLong()).isEqualTo(3);
        assertThat(summary.data().path("httpStatusCounts").path("200").asLong()).isEqualTo(3);
        assertThat(summary.data().path("httpStatusCounts").path("503").asLong()).isEqualTo(3);
        assertThat(summary.data().has("responseSummaries")).isFalse();
        assertThat(maximum.get()).isBetween(2, 3);
        assertThat(captured).hasSize(6);
        for (var actual : captured) {
            assertThat(actual.method()).isEqualTo("POST");
            byte[] decoded = parts[1].equals("GZIP") ? new GZIPInputStream(new ByteArrayInputStream(actual.body())).readAllBytes() : actual.body();
            assertThat(decoded).hasSize(128);
            assertThat(json.readTree(decoded).isObject()).isTrue();
            var headers = new TreeMap<String,List<String>>(String.CASE_INSENSITIVE_ORDER); headers.putAll(actual.headers());
            assertThat(headers.get("X-Test")).containsExactly("execution");
            if (chunked) { assertThat(headers.get("Transfer-Encoding")).containsExactly("chunked"); assertThat(headers).doesNotContainKey("Content-Length"); }
            else { assertThat(headers.get("Content-Length")).containsExactly(String.valueOf(actual.body().length)); }
        }
    }
}
