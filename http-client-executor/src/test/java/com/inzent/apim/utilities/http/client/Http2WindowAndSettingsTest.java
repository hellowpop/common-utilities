package com.inzent.apim.utilities.http.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.Http2Settings;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.RequestBody;

class Http2WindowAndSettingsTest {
	@TempDir
	static Path directory;

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY"})
	void parallelBodiesAndResponsesCrossFlowControlWindowAndSpillToFiles(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .memoryThreshold(1024)
		                                                                             .maxConnTotal(1)
		                                                                             .maxConnPerRoute(1)
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build()) {
			List<ExecutionContext> contexts = new ArrayList<>();
			List<CompletableFuture<Void>> executions = new ArrayList<>();
			try {
				for (int i = 0; i < 2; i++) {
					byte[] body = new byte[256 * 1024];
					Arrays.fill(body,
					            (byte) (65 + i));
					ExecutionContext context = ExecutionContext.builder(executor)
					                                           .url(URI.create("https://127.0.0.1:" + server.port()))
					                                           .body(RequestBody.bytes("application/octet-stream",
					                                                                   body))
					                                           .build();
					contexts.add(context);
					executions.add(CompletableFuture.runAsync(context::execute));
				}
				Http2MultiplexTest.Pending first = requests.poll(4,
				                                                 TimeUnit.SECONDS);
				Http2MultiplexTest.Pending second = requests.poll(4,
				                                                  TimeUnit.SECONDS);
				Assertions.assertNotNull(first);
				Assertions.assertNotNull(second);
				Assertions.assertEquals(first.connectionId(),
				                        second.connectionId());
				second.reply();
				first.reply();
				CompletableFuture.allOf(executions.toArray(CompletableFuture[]::new))
				                 .get(4,
				                      TimeUnit.SECONDS);
				for (int i = 0; i < contexts.size(); i++) {
					ExecutionContext context = contexts.get(i);
					context.throwIfFailed();
					byte[] expected = new byte[256 * 1024];
					Arrays.fill(expected,
					            (byte) (65 + i));
					Assertions.assertArrayEquals(expected,
					                             context.response()
					                                    .body()
					                                    .bytes(expected.length));
					Assertions.assertTrue(context.response()
					                             .body()
					                             .fileBacked());
				}
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
	                     "NETTY"})
	void loweringServerLimitPreservesActiveStreamsAndLimitsNewAdmissions(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .poolAcquireTimeout(Duration.ofSeconds(1))
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
				server.settingsAcks.clear();
				server.channels.get(0)
				               .writeAndFlush(new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(1)))
				               .sync();
				Assertions.assertNotNull(server.settingsAcks.poll(2,
				                                                  TimeUnit.SECONDS));
				ExecutionContext waiting = Http2MultiplexTest.context(executor,
				                                                      server.port(),
				                                                      (byte) 67);
				contexts.add(waiting);
				executions.add(CompletableFuture.runAsync(waiting::execute));
				executions.get(2)
				          .get(3,
				               TimeUnit.SECONDS);
				Assertions.assertNotNull(waiting.failure());
				Assertions.assertTrue(requests.isEmpty());
				first.reply();
				second.reply();
				executions.get(0)
				          .get(3,
				               TimeUnit.SECONDS);
				executions.get(1)
				          .get(3,
				               TimeUnit.SECONDS);
				contexts.get(0)
				        .throwIfFailed();
				contexts.get(1)
				        .throwIfFailed();
				ExecutionContext next = Http2MultiplexTest.context(executor,
				                                                   server.port(),
				                                                   (byte) 68);
				contexts.add(next);
				executions.add(CompletableFuture.runAsync(next::execute));
				Http2MultiplexTest.Pending received = requests.poll(3,
				                                                    TimeUnit.SECONDS);
				Assertions.assertNotNull(received);
				Assertions.assertArrayEquals(new byte[]{68},
				                             received.body());
				received.reply();
				executions.get(3)
				          .get(3,
				               TimeUnit.SECONDS);
				next.throwIfFailed();
				Assertions.assertEquals(1,
				                        server.connections.get());
			}
			finally {
				Http2MultiplexTest.cleanup(contexts,
				                           executions);
			}
		}
	}
}
