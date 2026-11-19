package com.inzent.apim.utilities.http.client;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.RequestBody;

class SafetyIntegrationTest {
	@TempDir
	Path directory;

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void truncatedResponsePreservesPartialBodyAndDeletesSpilledStores(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
				try (Socket socket = server.accept()) {
					Http1IntegrationTest.readHeaders(socket.getInputStream());
					socket.getOutputStream()
					      .write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc".getBytes(StandardCharsets.US_ASCII));
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			ConnectionConfigure settings = ConnectionConfigure.builder()
			                                                  .temporaryDirectory(this.directory)
			                                                  .memoryThreshold(1)
			                                                  .build();
			try (HttpClientManager manager = HttpClientManager.create(settings);
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build()) {
				ExecutionContext context = ExecutionContext.builder(executor)
				                                           .url(URI.create("http://localhost:" + server.getLocalPort()))
				                                           .build();
				try (context) {
					context.execute();
					Assertions.assertNotNull(context.failure());
					Assertions.assertFalse(context.response()
					                              .complete());
					Assertions.assertEquals("abc",
					                        new String(context.response()
					                                          .body()
					                                          .bytes(100),
					                                   StandardCharsets.US_ASCII));
					Assertions.assertTrue(context.response()
					                             .body()
					                             .fileBacked());
				}
				try (java.util.stream.Stream<Path> paths = Files.list(this.directory)) {
					Assertions.assertEquals(0,
					                        paths.count());
				}
			}
			served.get(3,
			           TimeUnit.SECONDS);
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void cancelUnblocksResponseReadAndClosesConnection(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CountDownLatch sent = new CountDownLatch(1);
			CompletableFuture<Integer> remote = CompletableFuture.supplyAsync(() -> {
				try (Socket socket = server.accept()) {
					socket.setSoTimeout(5000);
					Http1IntegrationTest.readHeaders(socket.getInputStream());
					sent.countDown();
					return socket.getInputStream()
					             .read();
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("http://localhost:" + server.getLocalPort()))
			                                                .build()) {
				CompletableFuture<Void> execution = CompletableFuture.runAsync(context::execute);
				Assertions.assertTrue(sent.await(3,
				                                 TimeUnit.SECONDS));
				context.cancel();
				execution.get(3,
				              TimeUnit.SECONDS);
				Assertions.assertEquals(ExecutionContext.State.CANCELLED,
				                        context.state());
				Assertions.assertEquals(-1,
				                        remote.get(3,
				                                   TimeUnit.SECONDS));
			}
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void shortRawBodyIsWrittenBeforeOutputShutdown(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<byte[]> remote = CompletableFuture.supplyAsync(() -> {
				try (Socket socket = server.accept()) {
					Http1IntegrationTest.readHeaders(socket.getInputStream());
					byte[] bytes = socket.getInputStream()
					                     .readAllBytes();
					socket.getOutputStream()
					      .write("HTTP/1.1 400 Bad\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					return bytes;
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("http://localhost:" + server.getLocalPort()))
			                                                .body(RequestBody.bytes("text/plain",
			                                                                        new byte[10]))
			                                                .shutdownOutput(true)
			                                                .interceptor(new ClientHandleInterceptor() {
				                                                @Override
				                                                public byte[] contentGenerated(byte[] bytes) {
					                                                return new byte[]{65};
				                                                }
			                                                })
			                                                .build()) {
				context.execute()
				       .throwIfFailed();
				Assertions.assertEquals(400,
				                        context.response()
				                               .status()
				                               .code());
				Assertions.assertArrayEquals(new byte[]{65},
				                             remote.get(3,
				                                        TimeUnit.SECONDS));
			}
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void rawLeaseCanReplaceIdleConnectionAtPerRouteLimit(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<Void> remote = CompletableFuture.runAsync(() -> {
				try {
					for (int i = 0; i < 2; i++) {
						try (Socket socket = server.accept()) {
							socket.setSoTimeout(3000);
							Http1IntegrationTest.readHeaders(socket.getInputStream());
							socket.getOutputStream()
							      .write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
							socket.getInputStream()
							      .read();
						}
					}
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			ConnectionConfigure settings = ConnectionConfigure.builder()
			                                                  .maxConnTotal(2)
			                                                  .maxConnPerRoute(1)
			                                                  .poolAcquireTimeout(Duration.ofMillis(200))
			                                                  .build();
			try (HttpClientManager manager = HttpClientManager.create(settings);
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build()) {
				URI uri = URI.create("http://localhost:" + server.getLocalPort());
				try (ExecutionContext first = ExecutionContext.builder(executor)
				                                              .url(uri)
				                                              .build()) {
					first.execute()
					     .throwIfFailed();
				}
				try (ExecutionContext second = ExecutionContext.builder(executor)
				                                               .url(uri)
				                                               .interceptor(new ClientHandleInterceptor() {
				                                               })
				                                               .build()) {
					second.execute()
					      .throwIfFailed();
				}
			}
			remote.get(4,
			           TimeUnit.SECONDS);
		}
	}
}
