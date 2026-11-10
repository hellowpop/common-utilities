package com.inzent.apim.utilities.http2mock;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import static org.assertj.core.api.Assertions.*;

class NettyMockFactoryTest {
    @TempDir Path directory;

    @Test void reusesNativeYamlOverridesCustomHeaderAndAnnotationProcessors() throws Exception {
        int port = MockServicesIntegrationTest.unusedPort();
        var file = directory.resolve("netty.yml");
        Files.writeString(file, """
                server:
                  host: 127.0.0.1
                  port: %d
                  worker-threads: 1
                  max-content-length: 2048
                mock:
                  header-name: x-custom-action
                  default-action: ok
                """.formatted(port));
        var prepared = NettyMockFactory.create(new MockServicesProperties.Netty(true, file.toUri().toString(),
                List.of("--server.max-content-length=4096")), new DefaultResourceLoader());
        assertThat(prepared.config().maxContentLength()).isEqualTo(4096);
        try (var server = prepared.server()) {
            server.start();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                    .header("x-custom-action", "status").header("x-status", "202").timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);
        }
    }

    @Test void rejectsUnknownNativeYamlSettingsBeforeOpeningAnySocket() throws Exception {
        var file = directory.resolve("invalid.yml");
        Files.writeString(file, "server:\n  worker-thread-typo: 1\n");
        assertThatThrownBy(() -> NettyMockFactory.create(new MockServicesProperties.Netty(true,
                file.toUri().toString(), List.of()), new DefaultResourceLoader())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void preservesNativeRegistryValidationForAbstractAnnotatedProcessors() throws Exception {
        var file = directory.resolve("invalid-processor.yml");
        Files.writeString(file, """
                server:
                  port: 28080
                mock:
                  scan-packages:
                    - com.inzent.apim.utilities.netty.http.mockserver.processor
                    - com.inzent.apim.utilities.http2mock.invalid
                """);
        assertThatThrownBy(() -> {
            try (var server = NettyMockFactory.create(new MockServicesProperties.Netty(true,
                    file.toUri().toString(), List.of()), new DefaultResourceLoader()).server()) { }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("public concrete AnnotationProcessor");
    }
}
