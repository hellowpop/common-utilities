package com.inzent.apim.utilities.http2mock;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

class MockServicesIntegrationTest {
    static int unusedPort() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private String[] arguments(int nettyPort) {
        return new String[]{"--server.port=0", "--mock.http.port=0", "--spring.main.banner-mode=off",
                "--mock.services.netty.enabled=true", "--mock.services.netty.arguments[0]=--server.port=" + nettyPort,
                "--mock.services.response.enabled=true",
                "--mock.services.response.bindings[0].name=configured-reply", "--mock.services.response.bindings[0].scenario=REPLY",
                "--mock.services.response.bindings[0].port=0", "--mock.services.response.bindings[0].reply.status=201",
                "--mock.services.response.bindings[0].reply.body=configured body",
                "--mock.services.response.bindings[1].name=wait", "--mock.services.response.bindings[1].scenario=WAIT_FOR_CLIENT_DISCONNECT",
                "--mock.services.response.bindings[1].port=0", "--mock.services.response.bindings[1].options.read-timeout-millis=2000",
                "--mock.services.response.bindings[2].name=slow", "--mock.services.response.bindings[2].scenario=SLOW_READ",
                "--mock.services.response.bindings[2].port=0", "--mock.services.response.bindings[2].options.read-interval=10ms",
                "--mock.services.response.bindings[3].name=close", "--mock.services.response.bindings[3].scenario=CLOSE",
                "--mock.services.response.bindings[3].port=0", "--mock.services.response.bindings[3].delay=120ms"};
    }
    private int webPort(org.springframework.context.ConfigurableApplicationContext context) {
        var web = (ServletWebServerApplicationContext) context;
        return Arrays.stream(((TomcatWebServer) web.getWebServer()).getTomcat().getService().findConnectors())
                .filter(c -> !c.getSecure()).findFirst().orElseThrow().getLocalPort();
    }
    private HttpResponse<String> control(int httpPort, String engine, String action, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/test/services/" + engine + "/" + action))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (origin != null) request.header("Origin", origin);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void consoleCanStopAndRestartEachEngineWithoutStoppingTheWebapp() throws Exception {
        int nettyPort = unusedPort();
        try (var context = new SpringApplicationBuilder(MockApplication.class).run(arguments(nettyPort))) {
            var services = context.getBean(MockServicesLifecycle.class);
            int http = webPort(context);
            String origin = "http://127.0.0.1:" + http;
            assertThat(control(http, "netty", "stop", origin).statusCode()).isEqualTo(200);
            assertThat(services.endpoints()).hasSize(4).allMatch(e -> e.engine().equals("response"));
            assertThatThrownBy(() -> new Socket("127.0.0.1", nettyPort)).isInstanceOf(java.io.IOException.class);
            assertThat(control(http, "netty", "stop", origin).statusCode()).isEqualTo(200);
            assertThat(control(http, "netty", "start", origin).statusCode()).isEqualTo(200);
            assertThat(control(http, "netty", "start", origin).statusCode()).isEqualTo(200);
            assertThat(request(nettyPort, "ok").body()).isEqualTo("OK");
            int https = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var tlsClient = new MockApplicationTest().client().version(HttpClient.Version.HTTP_2).build();
            String tlsOrigin = "https://localhost:" + https;
            for (String action : List.of("stop", "start")) {
                var tlsResult = tlsClient.send(HttpRequest.newBuilder(URI.create(tlsOrigin + "/test/services/netty/" + action))
                        .header("Origin", tlsOrigin).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(tlsResult.version()).isEqualTo(HttpClient.Version.HTTP_2);
                assertThat(tlsResult.statusCode()).isEqualTo(200);
            }
            int waitPort = services.endpoints().stream().filter(e -> e.name().equals("wait")).findFirst().orElseThrow().port();
            try (var waiting = new Socket("127.0.0.1", waitPort)) {
                waiting.setSoTimeout(3000);
                var stopped = control(http, "response", "stop", origin);
                assertThat(stopped.statusCode()).isEqualTo(200);
                assertThat(stopped.body()).contains("STOPPED", "response");
                assertThat(waiting.getInputStream().read()).isEqualTo(-1);
            }
            assertThat(services.endpoints()).hasSize(1);
            assertThat(request(nettyPort, "ok").statusCode()).isEqualTo(200);
            assertThat(control(http, "response", "start", origin).statusCode()).isEqualTo(200);
            assertThat(services.endpoints()).hasSize(5);
            int reply = services.endpoints().stream().filter(e -> e.name().equals("configured-reply")).findFirst().orElseThrow().port();
            assertThat(request(reply, "ok").body()).isEqualTo("configured body");
            assertThat(control(http, "response", "stop", origin).statusCode()).isEqualTo(200);
            assertThat(control(http, "netty", "stop", origin).statusCode()).isEqualTo(200);
            var page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(services.endpoints()).isEmpty();
        }
    }

    @Test void failedRestartLeavesOtherEngineRunningAndCanBeRetried() throws Exception {
        int nettyPort = unusedPort();
        try (var context = new SpringApplicationBuilder(MockApplication.class).run(arguments(nettyPort))) {
            int http = webPort(context);
            assertThat(control(http, "netty", "stop", null).statusCode()).isEqualTo(200);
            try (var occupied = new ServerSocket(nettyPort, 1, InetAddress.getByName("127.0.0.1"))) {
                var failure = control(http, "netty", "start", null);
                assertThat(failure.statusCode()).isEqualTo(409);
                var state = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http + "/test/service-engines")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(state.body()).contains("ERROR", "RUNNING");
                assertThat(context.getBean(MockServicesLifecycle.class).endpoints()).hasSize(4);
            }
            assertThat(control(http, "netty", "start", null).statusCode()).isEqualTo(200);
            assertThat(request(nettyPort, "ok").body()).isEqualTo("OK");
            assertThat(control(http, "response", "stop", "http://example.invalid").statusCode()).isEqualTo(403);
            assertThat(context.getBean(MockServicesLifecycle.class).endpoints()).hasSize(5);
            assertThat(control(http, "unknown", "start", null).statusCode()).isEqualTo(400);
            assertThat(control(http, "netty", "delete", null).statusCode()).isEqualTo(400);
        }
    }

    @Test void concurrentStartsAreIdempotentAndControlsRejectSimpleFormPosts() throws Exception {
        int nettyPort = unusedPort();
        try (var context = new SpringApplicationBuilder(MockApplication.class).run(arguments(nettyPort))) {
            int http = webPort(context);
            control(http, "netty", "stop", null);
            var client = HttpClient.newHttpClient();
            URI target = URI.create("http://127.0.0.1:" + http + "/test/services/netty/start");
            var startRequest = HttpRequest.newBuilder(target).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var first = client.sendAsync(startRequest, HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(startRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(context.getBean(MockServicesLifecycle.class).endpoints().stream().filter(e -> e.engine().equals("netty"))).hasSize(1);
            var form = client.send(HttpRequest.newBuilder(target).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("action=start")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(form.statusCode()).isEqualTo(415);
        }
    }
    private HttpResponse<String> request(int port, String action, String... extra) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .timeout(Duration.ofSeconds(5)).header("x-response-action", action);
        if (extra.length > 0) builder.header(extra[0], extra[1]);
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(builder.POST(HttpRequest.BodyPublishers.ofString("copied body")).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void servesAllEnginesTogetherAndReleasesTheirPortsOnShutdown() throws Exception {
        int nettyPort = unusedPort();
        List<Integer> ports = new ArrayList<>();
        try (var context = new SpringApplicationBuilder(MockApplication.class).run(arguments(nettyPort))) {
            var services = context.getBean(MockServicesLifecycle.class);
            assertThat(services.isRunning()).isTrue();
            assertThat(services.endpoints()).hasSize(5);
            ports.addAll(services.endpoints().stream().map(MockServicesLifecycle.Endpoint::port).toList());
            assertThat(request(nettyPort, "ok").body()).isEqualTo("OK");
            assertThat(request(nettyPort, "copy-and-send").body()).isEqualTo("copied body");
            assertThat(request(nettyPort, "status", "x-status", "418").statusCode()).isEqualTo(418);
            assertThat(request(nettyPort, "error-500").statusCode()).isEqualTo(500);
            int replyPort = services.endpoints().stream().filter(e -> e.name().equals("configured-reply")).findFirst().orElseThrow().port();
            var reply = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + replyPort + "/"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(reply.statusCode()).isEqualTo(201);
            assertThat(reply.body()).isEqualTo("configured body");
            for (String name : List.of("wait", "slow")) {
                int port = services.endpoints().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow().port();
                try (var client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(150);
                    client.getOutputStream().write("data".getBytes(StandardCharsets.UTF_8));
                    assertThatThrownBy(() -> client.getInputStream().read()).isInstanceOf(SocketTimeoutException.class);
                    client.shutdownOutput(); client.setSoTimeout(3000);
                    assertThat(client.getInputStream().read()).isEqualTo(-1);
                }
            }
            int closePort = services.endpoints().stream().filter(e -> e.name().equals("close")).findFirst().orElseThrow().port();
            long start = System.nanoTime();
            try (var client = new Socket("127.0.0.1", closePort)) {
                client.setSoTimeout(3000); assertThat(client.getInputStream().read()).isEqualTo(-1);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(90);
            var web = (ServletWebServerApplicationContext) context;
            int http = Arrays.stream(((TomcatWebServer) web.getWebServer()).getTomcat().getService().findConnectors())
                    .filter(c -> !c.getSecure()).findFirst().orElseThrow().getLocalPort();
            var status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http + "/test/services")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body()).contains("configured-reply", "netty", "RUNNING");
            services.stop(); services.stop();
            assertThat(services.endpoints()).isEmpty();
        }
        for (int port : ports) {
            try (var free = new ServerSocket()) {
                free.setReuseAddress(true); free.bind(new InetSocketAddress("127.0.0.1", port));
            }
        }
    }

    @Test void responseBindFailureRollsBackTheAlreadyStartedNettyServer() throws Exception {
        int nettyPort = unusedPort();
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var args = new ArrayList<>(List.of(arguments(nettyPort)));
            args.add("--mock.services.response.bindings[0].port=" + occupied.getLocalPort());
            // Replace the earlier value rather than passing a duplicate command-line property.
            args.remove("--mock.services.response.bindings[0].port=0");
            assertThatThrownBy(() -> {
                try (var ignored = new SpringApplicationBuilder(MockApplication.class).run(args.toArray(String[]::new))) { }
            }).isInstanceOf(RuntimeException.class);
        }
        try (var free = new ServerSocket()) {
            free.setReuseAddress(true); free.bind(new InetSocketAddress("127.0.0.1", nettyPort));
        }
    }

    @Test void webConnectorFailureAlsoReleasesAuxiliaryServices() throws Exception {
        int nettyPort = unusedPort();
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var args = new ArrayList<>(List.of(arguments(nettyPort)));
            args.remove("--mock.http.port=0");
            args.add("--mock.http.port=" + occupied.getLocalPort());
            assertThatThrownBy(() -> {
                try (var ignored = new SpringApplicationBuilder(MockApplication.class).run(args.toArray(String[]::new))) { }
            }).isInstanceOf(RuntimeException.class);
        }
        try (var free = new ServerSocket()) {
            free.setReuseAddress(true); free.bind(new InetSocketAddress("127.0.0.1", nettyPort));
        }
    }

    @Test void disabledEnginesDoNotLoadTheirConfigurationOrOpenPorts() throws Exception {
        try (var context = new SpringApplicationBuilder(MockApplication.class).run("--server.port=0", "--mock.http.port=0",
                "--mock.services.netty.enabled=false", "--mock.services.response.enabled=false",
                "--mock.services.netty.config=file:/does-not-exist.yml", "--spring.main.banner-mode=off")) {
            assertThat(context.getBean(MockServicesLifecycle.class).endpoints()).isEmpty();
            assertThat(control(webPort(context), "netty", "start", null).statusCode()).isEqualTo(409);
            assertThat(context.getBean(MockServicesLifecycle.class).engineStates()).allMatch(state -> state.state().equals("DISABLED"));
        }
    }

    @Test void rejectsInvalidResponseSocketConfigurationAndReleasesNetty() throws Exception {
        int nettyPort = unusedPort();
        var args = new ArrayList<>(List.of(arguments(nettyPort)));
        args.add("--mock.services.response.bindings[0].options.read-chunk-size=0");
        assertThatThrownBy(() -> {
            try (var ignored = new SpringApplicationBuilder(MockApplication.class).run(args.toArray(String[]::new))) { }
        }).isInstanceOf(RuntimeException.class);
        try (var free = new ServerSocket()) {
            free.setReuseAddress(true); free.bind(new InetSocketAddress("127.0.0.1", nettyPort));
        }
    }
}
