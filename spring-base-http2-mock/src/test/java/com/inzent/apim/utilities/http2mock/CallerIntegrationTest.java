package com.inzent.apim.utilities.http2mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MockApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.ssl.enabled=false", "mock.http.port=0",
                "mock.services.netty.enabled=false", "mock.services.response.enabled=false"})
class CallerIntegrationTest {
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .cookieHandler(new CookieManager()).build();
    final ObjectMapper json = new ObjectMapper();

    HttpResponse<String> send(String path, String method, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("X-CSRF-Token", token);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void callerConsoleAndExistingMockConsoleCoexist() throws Exception {
        assertThat(send("/", "GET", null, null).body()).contains("Protocol Lab", "/caller/");
        var page = send("/caller/", "GET", null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("REQUEST CALLER", "/caller/app.js");
        assertThat(send("/caller/app.js", "GET", null, null).statusCode()).isEqualTo(200);
        var echo = send("/api/echo", "POST", "hello", null);
        assertThat(echo.statusCode()).isEqualTo(200);
        assertThat(echo.headers().firstValue("Content-Security-Policy")).isEmpty();
    }

    @Test void callerPersistsScenariosWithCsrfAndCanExecuteAgainstEmbeddedMock() throws Exception {
        assertThat(send("/api/scenarios", "POST", "{}", null).statusCode()).isEqualTo(403);
        var token = json.readTree(send("/api/csrf", "GET", null, null).body()).path("token").asText();
        var example = (com.fasterxml.jackson.databind.node.ObjectNode)
                json.readTree(send("/api/example", "GET", null, null).body());
        example.put("endpointBaseUrl", "http://localhost:" + port);
        example.put("maxWorkers", 1); example.put("rampUpSeconds", 0); example.put("loopCount", 1);
        var path = (com.fasterxml.jackson.databind.node.ObjectNode) example.path("paths").get(0);
        path.put("requestPath", "/api/echo");
        var created = send("/api/scenarios", "POST", json.writeValueAsString(example), token);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var id = json.readTree(created.body()).path("id").asText();
        assertThat(created.headers().firstValue("Location")).hasValue("/api/scenarios/" + id);
        var started = send("/api/scenarios/" + id + "/runs", "POST", null, token);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        var runPath = started.headers().firstValue("Location").orElseThrow();
        com.fasterxml.jackson.databind.JsonNode run = null;
        for (int i = 0; i < 200; i++) {
            run = json.readTree(send(runPath, "GET", null, null).body());
            if (java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(run.path("status").asText())) break;
            Thread.sleep(50);
        }
        assertThat(run.path("status").asText()).as(run.toPrettyString()).isEqualTo("COMPLETED");
        assertThat(run.path("succeeded").asLong()).isPositive();
        assertThat(run.path("failed").asLong()).isZero();
        assertThat(send(runPath + "/report", "GET", null, null).statusCode()).isEqualTo(200);
    }
}
