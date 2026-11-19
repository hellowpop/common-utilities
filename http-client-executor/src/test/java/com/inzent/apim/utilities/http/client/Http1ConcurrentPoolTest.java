package com.inzent.apim.utilities.http.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class Http1ConcurrentPoolTest {
	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void waitingHttp1ExecutionReusesHealthyExclusiveConnection(ConnectorType type) throws Exception {
		List<Integer> ports = new CopyOnWriteArrayList<>();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService handlers = Executors.newCachedThreadPool();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",
		                                                            0),
		                                      0);
		server.setExecutor(handlers);
		server.createContext("/",
		                     exchange -> {
			                     exchange.getRequestBody()
			                             .readAllBytes();
			                     ports.add(exchange.getRemoteAddress()
			                                       .getPort());
			                     if (ports.size() == 1) {
				                     entered.countDown();
				                     try {
					                     release.await(3,
					                                   TimeUnit.SECONDS);
				                     }
				                     catch (InterruptedException failure) {
					                     Thread.currentThread()
					                           .interrupt();
				                     }
			                     }
			                     exchange.sendResponseHeaders(200,
			                                                  -1);
			                     exchange.close();
		                     });
		server.start();
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .maxConnTotal(1)
		                                                                             .maxConnPerRoute(1)
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .location(URI.create("http://127.0.0.1:" + server.getAddress()
		                                                                                           .getPort()))
		                                          .build();
		     ExecutionContext first = ExecutionContext.builder(executor)
		                                              .build();
		     ExecutionContext second = ExecutionContext.builder(executor)
		                                               .build()) {
			CompletableFuture<Void> a = CompletableFuture.runAsync(first::execute);
			Assertions.assertTrue(entered.await(2,
			                                    TimeUnit.SECONDS));
			CompletableFuture<Void> b = CompletableFuture.runAsync(second::execute);
			try {
				Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
				                        () -> b.get(150,
				                                    TimeUnit.MILLISECONDS));
			}
			finally {
				release.countDown();
			}
			CompletableFuture.allOf(a,
			                        b)
			                 .get(4,
			                      TimeUnit.SECONDS);
			first.throwIfFailed();
			second.throwIfFailed();
			Assertions.assertEquals(2,
			                        ports.size());
			Assertions.assertEquals(ports.get(0),
			                        ports.get(1));
		}
		finally {
			release.countDown();
			server.stop(0);
			handlers.shutdownNow();
		}
	}
}
