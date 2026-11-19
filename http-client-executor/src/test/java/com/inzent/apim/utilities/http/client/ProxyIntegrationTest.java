package com.inzent.apim.utilities.http.client;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class ProxyIntegrationTest {
	@TempDir
	static Path directory;

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void httpProxyReceivesAbsoluteTargetAndBasicCredentials(ConnectorType type) throws Exception {
		try (ServerSocket proxy = new ServerSocket(0)) {
			CompletableFuture<String> wire = CompletableFuture.supplyAsync(() -> {
				try (Socket socket = proxy.accept()) {
					String headers = new String(Http1IntegrationTest.readHeaders(socket.getInputStream()),
					                            StandardCharsets.ISO_8859_1);
					socket.getOutputStream()
					      .write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					return headers;
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			ConnectionConfigure configure = ConnectionConfigure.builder()
			                                                   .proxy(ProxyConfigure.basic("localhost",
			                                                                               proxy.getLocalPort(),
			                                                                               "user",
			                                                                               "pass"))
			                                                   .build();
			try (HttpClientManager manager = HttpClientManager.create(configure);
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("http://example.invalid:9191/a%2Fb?q=x"))
			                                                .build()) {
				context.execute()
				       .throwIfFailed();
				String received = wire.get(3,
				                           TimeUnit.SECONDS);
				Assertions.assertTrue(received.startsWith("GET http://example.invalid:9191/a%2Fb?q=x HTTP/1.1\r\n"),
				                      received);
				Assertions.assertTrue(received.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"));
				Assertions.assertEquals("***",
				                        context.request()
				                               .headers()
				                               .redacted()
				                               .first("Proxy-Authorization"));
			}
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void httpsProxyConnectPreservesMutualTls(ConnectorType type) throws Exception {
		try (TlsFixture target = new TlsFixture(directory,
		                                        false,
		                                        true);
		     ServerSocket proxy = new ServerSocket(0)) {
			CompletableFuture<String> tunnel = CompletableFuture.supplyAsync(() -> {
				try (Socket incoming = proxy.accept();
				     Socket outgoing = new Socket("localhost",
				                                  target.port())) {
					incoming.setSoTimeout(5000);
					outgoing.setSoTimeout(5000);
					String headers = new String(Http1IntegrationTest.readHeaders(incoming.getInputStream()),
					                            StandardCharsets.ISO_8859_1);
					incoming.getOutputStream()
					        .write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					CompletableFuture<Void> upstream = CompletableFuture.runAsync(() -> {
						try {
							incoming.getInputStream()
							        .transferTo(outgoing.getOutputStream());
							outgoing.shutdownOutput();
						}
						catch (Exception ignored) {
						}
					});
					outgoing.getInputStream()
					        .transferTo(incoming.getOutputStream());
					upstream.get(5,
					             TimeUnit.SECONDS);
					return headers;
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			ConnectionConfigure settings = ConnectionConfigure.builder()
			                                                  .sslContext(target.client)
			                                                  .proxy(ProxyConfigure.basic("localhost",
			                                                                              proxy.getLocalPort(),
			                                                                              "user",
			                                                                              "pass"))
			                                                  .build();
			try (HttpClientManager manager = HttpClientManager.create(settings);
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("https://localhost:" + target.port() + "/"))
			                                                .build()) {
				context.execute()
				       .throwIfFailed();
				Assertions.assertEquals(200,
				                        context.response()
				                               .status()
				                               .code());
			}
			String received = tunnel.get(6,
			                             TimeUnit.SECONDS);
			Assertions.assertTrue(received.startsWith("CONNECT localhost:" + target.port() + " HTTP/1.1\r\n"));
			Assertions.assertTrue(received.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"));
		}
	}
}
