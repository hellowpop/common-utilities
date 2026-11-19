package com.inzent.apim.utilities.http.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class Http2IdleTest {
	@TempDir
	static Path directory;

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY"})
	void responseIdleTimeoutAppliesBeforeHeaders(ConnectorType type) throws Exception {
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        false);
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .responseIdleTimeout(Duration.ofMillis(150))
		                                                                             .executionTimeout(Duration.ofSeconds(5))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("https://localhost:" + server.port() + "/stall"))
		                                                .build()) {
			long started = System.nanoTime();
			context.execute();
			Assertions.assertNotNull(context.failure());
			Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - started)
			                              .compareTo(Duration.ofSeconds(4)) < 0,
			                      "idle timeout must precede 5s execution deadline");
		}
	}
}
