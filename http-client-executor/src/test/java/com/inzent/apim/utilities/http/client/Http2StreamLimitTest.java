package com.inzent.apim.utilities.http.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class Http2StreamLimitTest {
	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void cancellingDuringGoAwayDoesNotAbortOtherDrainingStream(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .maxConnTotal(1)
		                                                                             .maxConnPerRoute(1)
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build()) {
			List<ExecutionContext> contexts = new ArrayList<>();
			List<CompletableFuture<Void>> executions = new ArrayList<>();
			try {
				for (byte value : new byte[]{65,
				                             66}) {
					ExecutionContext context = Http2MultiplexTest.context(executor,
					                                                      server.port(),
					                                                      value);
					contexts.add(context);
					executions.add(CompletableFuture.runAsync(context::execute));
				}
				Http2MultiplexTest.Pending first = requests.poll(3,
				                                                 TimeUnit.SECONDS);
				Http2MultiplexTest.Pending second = requests.poll(3,
				                                                  TimeUnit.SECONDS);
				Assertions.assertNotNull(first);
				Assertions.assertNotNull(second);
				io.netty.channel.Channel parent = first.context()
				                                       .channel()
				                                       .parent();
				parent.writeAndFlush(new io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame(io.netty.handler.codec.http2.Http2Error.NO_ERROR))
				      .sync();
				contexts.get(0)
				        .cancel();
				executions.get(0)
				          .get(3,
				               TimeUnit.SECONDS);
				Http2MultiplexTest.Pending sibling = first.body()[0] == 66
				                                     ? first
				                                     : second;
				Assertions.assertTrue(sibling.context()
				                             .channel()
				                             .isActive());
				sibling.reply();
				executions.get(1)
				          .get(3,
				               TimeUnit.SECONDS);
				contexts.get(1)
				        .throwIfFailed();
				Assertions.assertArrayEquals(new byte[]{66},
				                             contexts.get(1)
				                                     .response()
				                                     .body()
				                                     .bytes(100));
			}
			finally {
				Http2MultiplexTest.cleanup(contexts,
				                           executions);
			}
		}
	}

	@TempDir
	static Path directory;

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void configuredStreamLimitTimesOutWithoutLateTransmission(ConnectorType type) throws Exception {
		this.checkLimit(type,
		                10,
		                1);
	}

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void serverStreamLimitTimesOutWithoutLateTransmission(ConnectorType type) throws Exception {
		this.checkLimit(type,
		                1,
		                10);
	}

	private void checkLimit(ConnectorType type,
	                        int serverLimit,
	                        int clientLimit) throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        serverLimit,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .maxConnTotal(1)
		                                                                             .maxConnPerRoute(1)
		                                                                             .maxConcurrentStreamsPerConnection(clientLimit)
		                                                                             .poolAcquireTimeout(Duration.ofSeconds(1))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build()) {
			List<ExecutionContext> contexts = new ArrayList<>();
			List<CompletableFuture<Void>> executions = new ArrayList<>();
			try {
				ExecutionContext first = Http2MultiplexTest.context(executor,
				                                                    server.port(),
				                                                    (byte) 65);
				contexts.add(first);
				executions.add(CompletableFuture.runAsync(first::execute));
				Http2MultiplexTest.Pending active = requests.poll(3,
				                                                  TimeUnit.SECONDS);
				Assertions.assertNotNull(active);
				ExecutionContext waiting = Http2MultiplexTest.context(executor,
				                                                      server.port(),
				                                                      (byte) 66);
				contexts.add(waiting);
				executions.add(CompletableFuture.runAsync(waiting::execute));
				executions.get(1)
				          .get(3,
				               TimeUnit.SECONDS);
				Assertions.assertNotNull(waiting.failure());
				Assertions.assertFalse(waiting.requestSent());
				Assertions.assertTrue(requests.isEmpty(),
				                      "blocked stream must not be sent");
				active.reply();
				executions.get(0)
				          .get(3,
				               TimeUnit.SECONDS);
				first.throwIfFailed();
				ExecutionContext third = Http2MultiplexTest.context(executor,
				                                                    server.port(),
				                                                    (byte) 67);
				contexts.add(third);
				executions.add(CompletableFuture.runAsync(third::execute));
				Http2MultiplexTest.Pending next = requests.poll(3,
				                                                TimeUnit.SECONDS);
				Assertions.assertNotNull(next);
				Assertions.assertArrayEquals(new byte[]{67},
				                             next.body(),
				                             "timed out queued request must not appear later");
				Assertions.assertEquals(active.connectionId(),
				                        next.connectionId());
				next.reply();
				executions.get(2)
				          .get(3,
				               TimeUnit.SECONDS);
				third.throwIfFailed();
				Assertions.assertNull(requests.poll(100,
				                                    TimeUnit.MILLISECONDS));
				Assertions.assertEquals(1,
				                        server.connections.get());
			}
			finally {
				Http2MultiplexTest.cleanup(contexts,
				                           executions);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void goAwayDrainsExistingStreamAndNextExecutionUsesNewConnection(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .maxConnTotal(1)
		                                                                             .maxConnPerRoute(1)
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build()) {
			List<ExecutionContext> contexts = new ArrayList<>();
			List<CompletableFuture<Void>> executions = new ArrayList<>();
			try {
				ExecutionContext first = Http2MultiplexTest.context(executor,
				                                                    server.port(),
				                                                    (byte) 65);
				contexts.add(first);
				executions.add(CompletableFuture.runAsync(first::execute));
				Http2MultiplexTest.Pending active = requests.poll(3,
				                                                  TimeUnit.SECONDS);
				Assertions.assertNotNull(active);
				active.context()
				      .channel()
				      .parent()
				      .writeAndFlush(new io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame(io.netty.handler.codec.http2.Http2Error.NO_ERROR))
				      .sync();
				active.reply();
				executions.get(0)
				          .get(3,
				               TimeUnit.SECONDS);
				first.throwIfFailed();
				ExecutionContext next = Http2MultiplexTest.context(executor,
				                                                   server.port(),
				                                                   (byte) 66);
				contexts.add(next);
				executions.add(CompletableFuture.runAsync(next::execute));
				Http2MultiplexTest.Pending replacement = requests.poll(3,
				                                                       TimeUnit.SECONDS);
				Assertions.assertNotNull(replacement);
				Assertions.assertNotEquals(active.connectionId(),
				                           replacement.connectionId());
				replacement.reply();
				executions.get(1)
				          .get(3,
				               TimeUnit.SECONDS);
				next.throwIfFailed();
				Assertions.assertEquals(2,
				                        server.connections.get());
			}
			finally {
				Http2MultiplexTest.cleanup(contexts,
				                           executions);
			}
		}
	}
}
