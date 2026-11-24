package com.inzent.apim.utilities.http.client;

import java.net.URI;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

import com.inzent.apim.utilities.http.client.config.ProxyConfigure;

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

	@Test
	void javaServerTrustIsScopedToOneExecutor() throws Exception {
		try (TlsFixture server = new TlsFixture(directory,
		                                        false,
		                                        false);
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor relaxed = manager.executorBuilder(ConnectorType.JAVA)
		                                         .verifyServerCertificate(false)
		                                         .build();
		     HttpClientExecutor strict = manager.executorBuilder(ConnectorType.JAVA)
		                                        .build()) {
			URI url = URI.create("https://localhost:" + server.port() + "/");
			try (ExecutionContext accepted = ExecutionContext.builder(relaxed)
			                                                 .url(url)
			                                                 .build();
			     ExecutionContext rejected = ExecutionContext.builder(strict)
			                                                 .url(url)
			                                                 .build()) {
				accepted.execute()
				        .throwIfFailed();
				Assertions.assertEquals(200,
				                        accepted.response()
				                                .status()
				                                .code());
				Assertions.assertInstanceOf(SSLHandshakeException.class,
				                            rejected.execute()
				                                    .failure());
			}
			try (TunnelProxy proxy = new TunnelProxy(server.port(),
			                                         null);
			     HttpClientExecutor tunneled = manager.executorBuilder(ConnectorType.JAVA)
			                                          .verifyServerCertificate(false)
			                                          .configure(builder -> builder.proxy(ProxyConfigure.http("localhost",
			                                                                                                  proxy.port())))
			                                          .build();
			     ExecutionContext mismatch = ExecutionContext.builder(tunneled)
			                                                 .url(URI.create("https://wrong.invalid:" + server.port() + "/"))
			                                                 .build()) {
				Assertions.assertInstanceOf(SSLHandshakeException.class,
				                            mismatch.execute()
				                                    .failure());
			}
			Assertions.assertThrows(IllegalArgumentException.class,
			                        () -> manager.executorBuilder(ConnectorType.HT5)
			                                     .verifyServerCertificate(false));
			Assertions.assertThrows(IllegalArgumentException.class,
			                        () -> manager.executorBuilder(ConnectorType.JAVA)
			                                     .verifyServerCertificate(false)
			                                     .configure(builder -> builder.sslContext(server.client))
			                                     .build());
		}
	}

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
