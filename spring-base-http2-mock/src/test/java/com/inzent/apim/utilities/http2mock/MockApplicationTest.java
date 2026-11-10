package com.inzent.apim.utilities.http2mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = MockApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock.http.port=0", "mock.services.netty.enabled=false", "mock.services.response.enabled=false"})
class MockApplicationTest {
    @LocalServerPort int port;
    @Autowired ServletWebServerApplicationContext serverContext;
    final ObjectMapper json = new ObjectMapper();

    URI uri(String path) { return URI.create("https://localhost:" + port + path); }
    int httpPort() {
        var connectors = ((TomcatWebServer) serverContext.getWebServer()).getTomcat().getService().findConnectors();
        var http = Arrays.stream(connectors).filter(connector -> !connector.getSecure()).toList();
        assertThat(http).as("one additional plaintext HTTP connector").hasSize(1);
        assertThat(http.get(0).findUpgradeProtocols()).isEmpty();
        return http.get(0).getLocalPort();
    }
    URI httpUri(String path) { return URI.create("http://localhost:" + httpPort() + path); }

    HttpClient.Builder client() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (var input = getClass().getResourceAsStream("/tls/localhost.crt")) {
            trust.setCertificateEntry("localhost", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5));
    }
    HttpResponse<String> request(String path, HttpClient.Version version, String method) throws Exception {
        return request(uri(path), version, method);
    }
    HttpResponse<String> request(URI target, HttpClient.Version version, String method) throws Exception {
        return client().version(version).build()
                .send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20))
                        .header("X-Echo", "first").header("X-Echo", "second")
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest @ValueSource(strings = {"http", "https"})
    void servesBrowserConsoleAndActualConnectorPorts(String scheme) throws Exception {
        URI page = scheme.equals("http") ? httpUri("/") : uri("/");
        var response = request(page, HttpClient.Version.HTTP_1_1, "GET");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/html");
        var config = request(page.resolve("/test/config"), HttpClient.Version.HTTP_1_1, "GET");
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(config.headers().firstValue("cache-control").orElse("")).contains("no-store");
        var ports = json.readTree(config.body());
        assertThat(ports.path("httpPort").asInt()).isEqualTo(httpPort());
        assertThat(ports.path("httpsPort").asInt()).isEqualTo(port);
        for (String asset : new String[]{"/test-console.js", "/test-console.css"}) {
            assertThat(request(page.resolve(asset), HttpClient.Version.HTTP_1_1, "GET").statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"https GET", "https POST", "https PUT", "https DELETE", "http GET", "http POST", "http PUT", "http DELETE"})
    void echoesRequestHeadersForEveryMethod(String transportAndMethod) throws Exception {
        String[] parts = transportAndMethod.split(" ");
        String method = parts[1];
        URI target = parts[0].equals("http") ? httpUri("/api/echo?name=test") : uri("/api/echo?name=test");
        var response = request(target, HttpClient.Version.HTTP_1_1, method);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("method").asText()).isEqualTo(method);
        assertThat(body.path("uri").asText()).isEqualTo("/api/echo");
        assertThat(body.path("query").asText()).isEqualTo("name=test");
        assertThat(body.path("protocol").asText()).isEqualTo("HTTP/1.1");
        assertThat(body.path("headers").path("x-echo")).hasSize(2);
    }

    @Test void negotiatesHttp2OverTlsAndAcknowledges() throws Exception {
        var response = request("/http2/ping", HttpClient.Version.HTTP_2, "GET");
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("ack").asBoolean()).isTrue();
        assertThat(response.sslSession()).isPresent();
        assertThat(response.sslSession().orElseThrow().getProtocol()).isIn("TLSv1.2", "TLSv1.3");
    }

    @Test void selfSignedCertificateRequiresExplicitTrust() {
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/http2/ping")).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString())).isInstanceOf(SSLHandshakeException.class);
    }

    @Test void plaintextHttpCannotServeTheApi() throws Exception {
        var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/echo")).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @ParameterizedTest @ValueSource(strings = {"/http2/ping", "/%68ttp2/ping", "/http2;v=1/ping"})
    void httpConnectorRejectsHttp2PathsEvenWhenClientRequestsUpgrade(String path) throws Exception {
        var response = request(httpUri(path), HttpClient.Version.HTTP_2, "GET");
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.sslSession()).isEmpty();
        assertThat(json.readTree(response.body()).path("ack").isMissingNode()).isTrue();
    }
    @Test void rejectsWrongProtocolAndUnsupportedMethod() throws Exception {
        assertThat(request("/http2/ping", HttpClient.Version.HTTP_1_1, "GET").statusCode()).isEqualTo(505);
        var rejected = request("/api/echo", HttpClient.Version.HTTP_2, "GET");
        assertThat(rejected.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(rejected.statusCode()).isEqualTo(505);
        assertThat(request("/api/echo", HttpClient.Version.HTTP_1_1, "PATCH").statusCode()).isEqualTo(405);
    }

    @ParameterizedTest @ValueSource(strings = {"/%61pi/echo", "/api;v=1/echo"})
    void protocolRestrictionUsesTheSamePathAsServletRouting(String path) throws Exception {
        assertThat(request(path, HttpClient.Version.HTTP_2, "GET").statusCode()).isEqualTo(505);
    }

    @ParameterizedTest @ValueSource(strings = {"/%68ttp2/ping", "/http2;v=1/ping"})
    void http1RequestsCannotBypassRestrictionUsingEncodedPaths(String path) throws Exception {
        assertThat(request(path, HttpClient.Version.HTTP_1_1, "GET").statusCode()).isEqualTo(505);
    }

    @ParameterizedTest @ValueSource(strings = {"https HTTP_1_1", "https HTTP_2", "http HTTP_1_1"})
    void sseEmitsTenTimesOverTenSecondsAndCompletes(String transportAndVersion) throws Exception {
        String[] parts = transportAndVersion.split(" ");
        String version = parts[1];
        URI target = parts[0].equals("http") ? httpUri("/sse/call") : uri("/sse/call");
        long start = System.nanoTime();
        var response = client().version(HttpClient.Version.valueOf(version)).build().send(
                HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(HttpClient.Version.valueOf(version));
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        var data = new ArrayList<String>();
        var arrivals = new ArrayList<Long>();
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) { data.add(line); arrivals.add(System.nanoTime()); }
            }
        }
        assertThat(data).hasSize(10);
        for (int i = 0; i < 10; i++) {
            var event = json.readTree(data.get(i).substring(5));
            assertThat(event.path("sequence").asInt()).isEqualTo(i + 1);
            Instant.parse(event.path("serverTime").asText());
            if (i > 0) assertThat(Duration.ofNanos(arrivals.get(i) - arrivals.get(i - 1)).toMillis()).isBetween(500L, 2000L);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(9500L, 16000L);
    }

    @ParameterizedTest @ValueSource(strings = {"ws", "wss"})
    void websocketEmitsTenTimesAndClosesNormally(String scheme) throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var times = new CopyOnWriteArrayList<Long>();
        var closed = new CompletableFuture<Integer>();
        var text = new StringBuilder();
        long start = System.nanoTime();
        WebSocket socket = client().build().newWebSocketBuilder()
                .buildAsync(URI.create(scheme + "://localhost:" + (scheme.equals("ws") ? httpPort() : port) + "/ws/call"), new WebSocket.Listener() {
                    public void onOpen(WebSocket ws) { ws.request(1); }
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) { messages.add(text.toString()); times.add(System.nanoTime()); text.setLength(0); }
                        ws.request(1); return null;
                    }
                    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                        closed.complete(status); return null;
                    }
                    public void onError(WebSocket ws, Throwable error) { closed.completeExceptionally(error); }
                }).get(5, TimeUnit.SECONDS);
        try {
            assertThat(closed.get(16, TimeUnit.SECONDS)).isEqualTo(1000);
            assertThat(messages).hasSize(10);
            for (int i = 0; i < 10; i++) {
                JsonNode event = json.readTree(messages.get(i));
                assertThat(event.path("sequence").asInt()).isEqualTo(i + 1);
                Instant.parse(event.path("serverTime").asText());
                if (i > 0) assertThat(Duration.ofNanos(times.get(i) - times.get(i - 1)).toMillis()).isBetween(500L, 2000L);
            }
            assertThat(Duration.ofNanos(times.get(9) - start).toMillis()).isBetween(9500L, 16000L);
        } finally { socket.abort(); }
    }
}
