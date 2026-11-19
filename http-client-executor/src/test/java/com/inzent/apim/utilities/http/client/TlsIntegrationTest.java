package com.inzent.apim.utilities.http.client;

import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.RequestBody;

class TlsIntegrationTest {
	@TempDir
	static Path directory;

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void mutualTlsUsesInjectedSslContext(ConnectorType type) throws Exception {
		try (TlsFixture server = new TlsFixture(directory,
		                                        false,
		                                        true);
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("https://localhost:" + server.port() + "/"))
		                                                .body(RequestBody.bytes("text/plain",
		                                                                        new byte[]{65,
		                                                                                   66}))
		                                                .build()) {
			context.execute()
			       .throwIfFailed();
			Assertions.assertArrayEquals(new byte[]{65,
			                                        66},
			                             context.response()
			                                    .body()
			                                    .bytes(100));
		}
	}

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY"})
	void http2IsNegotiatedWithoutDowngrade(ConnectorType type) throws Exception {
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true);
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build()) {
			for (int i = 0; i < 2; i++) {
				try (ExecutionContext context = ExecutionContext.builder(executor)
				                                                .url(URI.create("https://localhost:" + server.port() + "/"))
				                                                .body(RequestBody.bytes("text/plain",
				                                                                        "hello".getBytes(StandardCharsets.US_ASCII)))
				                                                .build()) {
					context.execute()
					       .throwIfFailed();
					Assertions.assertEquals(ConnectVersion.HTTP_2,
					                        context.response()
					                               .version());
					Assertions.assertEquals("hello",
					                        new String(context.response()
					                                          .body()
					                                          .bytes(100),
					                                   StandardCharsets.US_ASCII));
				}
			}
		}
	}
}
