package com.inzent.apim.utilities.http.client;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.RequestBody;

class ReviewRegressionTest {
	@Test
	void detachingAbortWaitsForInFlightCancellationBeforeLeaseReuse() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://localhost:1"))
		                                                .build()) {
			context.begin();
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			context.abortWith(() -> {
				entered.countDown();
				try {
					release.await();
				}
				catch (InterruptedException failure) {
					Thread.currentThread()
					      .interrupt();
				}
			});
			CompletableFuture<Void> cancelled = CompletableFuture.runAsync(context::cancel);
			Assertions.assertTrue(entered.await(1,
			                                    TimeUnit.SECONDS));
			CountDownLatch detaching = new CountDownLatch(1);
			CompletableFuture<Void> detached = CompletableFuture.runAsync(() -> {
				detaching.countDown();
				context.abortWith(null);
			});
			try {
				Assertions.assertTrue(detaching.await(1,
				                                      TimeUnit.SECONDS));
				Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
				                        () -> detached.get(100,
				                                           TimeUnit.MILLISECONDS));
			}
			finally {
				release.countDown();
				cancelled.get(2,
				              TimeUnit.SECONDS);
				detached.get(2,
				             TimeUnit.SECONDS);
				context.finish(new java.util.concurrent.CancellationException());
			}
		}
	}

	@Test
	void foreignExecutorCannotChangeContextsTransport() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor first = manager.executorBuilder(ConnectorType.JAVA)
		                                       .build();
		     HttpClientExecutor other = manager.executorBuilder(ConnectorType.HT5)
		                                       .build();
		     ExecutionContext context = ExecutionContext.builder(first)
		                                                .url(URI.create("http://localhost:1"))
		                                                .build()) {
			Assertions.assertThrows(IllegalArgumentException.class,
			                        () -> other.execute(context));
			Assertions.assertEquals(ExecutionContext.State.CREATED,
			                        context.state());
		}
	}

	@Test
	void managerCloseFromCallbackLeavesManagerUsableForLaterCleanup() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://localhost:1"))
		                                                .interceptor(new ClientHandleInterceptor() {
			                                                @Override
			                                                public byte[] contentGenerated(byte[] bytes) {
				                                                Assertions.assertThrows(IllegalStateException.class,
				                                                                        manager::close);
				                                                return null;
			                                                }
		                                                })
		                                                .build()) {
			context.execute();
			try (HttpClientExecutor second = manager.executorBuilder(ConnectorType.NETTY)
			                                        .build()) {
				Assertions.assertEquals(ConnectorType.NETTY,
				                        second.connectorType());
			}
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void unicodeUriIsPercentEncodedOnTheWire(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<String> remote = CompletableFuture.supplyAsync(() -> {
				try (Socket socket = server.accept()) {
					String head = new String(Http1IntegrationTest.readHeaders(socket.getInputStream()),
					                         StandardCharsets.ISO_8859_1);
					socket.getOutputStream()
					      .write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					return head;
				}
				catch (Exception failure) {
					throw new RuntimeException(failure);
				}
			});
			try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("http://localhost:" + server.getLocalPort() + "/한글?q=é&old=%2F"))
			                                                .build()) {
				context.execute()
				       .throwIfFailed();
				Assertions.assertTrue(remote.get(3,
				                                 TimeUnit.SECONDS)
				                            .startsWith("GET /%ED%95%9C%EA%B8%80?q=%C3%A9&old=%2F HTTP/1.1\r\n"));
			}
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void interruptUnblocksClassicSocketReadPromptly(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CountDownLatch requested = new CountDownLatch(1);
			CompletableFuture<Void> remote = CompletableFuture.runAsync(() -> {
				try (Socket socket = server.accept()) {
					socket.setSoTimeout(5000);
					Http1IntegrationTest.readHeaders(socket.getInputStream());
					requested.countDown();
					socket.getInputStream()
					      .read();
				}
				catch (Exception failure) {
					throw new RuntimeException(failure);
				}
			});
			try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("http://localhost:" + server.getLocalPort()))
			                                                .build()) {
				CountDownLatch finished = new CountDownLatch(1);
				AtomicBoolean interruptRetained = new AtomicBoolean();
				Thread worker = new Thread(() -> {
					try {
						context.execute();
					}
					finally {
						interruptRetained.set(Thread.currentThread()
						                            .isInterrupted());
						finished.countDown();
					}
				});
				worker.start();
				try {
					Assertions.assertTrue(requested.await(3,
					                                      TimeUnit.SECONDS));
					worker.interrupt();
					Assertions.assertTrue(finished.await(1,
					                                     TimeUnit.SECONDS),
					                      "interrupt must cancel active socket read");
					Assertions.assertEquals(ExecutionContext.State.CANCELLED,
					                        context.state());
					Assertions.assertTrue(interruptRetained.get());
				}
				finally {
					context.cancel();
					worker.join(3000);
				}
			}
			remote.get(3,
			           TimeUnit.SECONDS);
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void earlyFinalResponseIsPreservedWhenServerStopsReadingUpload(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CountDownLatch release = new CountDownLatch(1);
			CompletableFuture<Void> remote = CompletableFuture.runAsync(() -> {
				try (Socket socket = server.accept()) {
					Http1IntegrationTest.readHeaders(socket.getInputStream());
					socket.getOutputStream()
					      .write("HTTP/1.1 413 Too Large\r\nContent-Length: 3\r\nConnection: close\r\n\r\nerr".getBytes(StandardCharsets.US_ASCII));
					release.await(5,
					              TimeUnit.SECONDS);
				}
				catch (Exception failure) {
					throw new RuntimeException(failure);
				}
			});
			ConnectionConfigure settings = ConnectionConfigure.builder()
			                                                  .sendBufferSize(1024)
			                                                  .executionTimeout(Duration.ofSeconds(2))
			                                                  .build();
			try (HttpClientManager manager = HttpClientManager.create(settings);
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("http://localhost:" + server.getLocalPort()))
			                                                .body(RequestBody.bytes("application/octet-stream",
			                                                                        new byte[4 * 1024 * 1024]))
			                                                .build()) {
				context.execute()
				       .throwIfFailed();
				Assertions.assertEquals(413,
				                        context.response()
				                               .status()
				                               .code());
				Assertions.assertTrue(context.response()
				                             .complete());
				Assertions.assertFalse(context.requestSent());
			}
			finally {
				release.countDown();
			}
			remote.get(3,
			           TimeUnit.SECONDS);
		}
	}

	@Test
	void nettyConnectProxyResolvesOriginRemotely() throws Exception {
		try (ServerSocket proxy = new ServerSocket(0)) {
			proxy.setSoTimeout(1000);
			CompletableFuture<String> remote = CompletableFuture.supplyAsync(() -> {
				try (Socket socket = proxy.accept()) {
					String head = new String(Http1IntegrationTest.readHeaders(socket.getInputStream()),
					                         StandardCharsets.ISO_8859_1);
					socket.getOutputStream()
					      .write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					return head;
				}
				catch (Exception failure) {
					throw new RuntimeException(failure);
				}
			});
			try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
			                                                                             .proxy(ProxyConfigure.http("localhost",
			                                                                                                        proxy.getLocalPort()))
			                                                                             .build());
			     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.NETTY)
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .url(URI.create("https://only-at-proxy.invalid/"))
			                                                .build()) {
				context.execute();
				Assertions.assertNotNull(context.failure());
				Assertions.assertTrue(remote.get(2,
				                                 TimeUnit.SECONDS)
				                            .startsWith("CONNECT only-at-proxy.invalid:443 HTTP/1.1\r\n"));
			}
		}
	}
}
