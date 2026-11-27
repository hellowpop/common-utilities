package com.inzent.apim.utilities.http.client;

import java.net.URI;
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

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.RequestBody;

class Http2MultiplexTest {
	@TempDir
	static Path directory;

	record Pending(ChannelHandlerContext context,
	               byte[] body,
	               String proxyAuthorization) {
		static Pending copy(ChannelHandlerContext context,
		                    FullHttpRequest request) {
			byte[] bytes = new byte[request.content()
			                               .readableBytes()];
			request.content()
			       .getBytes(request.content()
			                        .readerIndex(),
			                 bytes);
			return new Pending(context,
			                   bytes,
			                   request.headers()
			                          .get("proxy-authorization"));
		}

		String connectionId() {
			return this.context.channel()
			                   .parent()
			                   .id()
			                   .asLongText();
		}

		String remoteHost() {
			return ((java.net.InetSocketAddress) this.context.channel()
			                                                 .parent()
			                                                 .remoteAddress()).getAddress()
			                                                                  .getHostAddress();
		}

		void reply() {
			DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
			                                                               HttpResponseStatus.OK,
			                                                               Unpooled.wrappedBuffer(this.body));
			response.headers()
			        .set("content-length",
			             this.body.length);
			this.context.writeAndFlush(response);
		}
	}

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void onePhysicalConnectionCarriesConcurrentIndependentStreams(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Pending.copy(context,
		                                                                                        request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .maxConnTotal(1)
		                                                                             .maxConnPerRoute(1)
		                                                                             .poolAcquireTimeout(Duration.ofSeconds(2))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(type)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build()) {
			List<ExecutionContext> contexts = new ArrayList<>();
			List<CompletableFuture<Void>> executions = new ArrayList<>();
			try {
				for (int i = 0; i < 3; i++) {
					ExecutionContext context = context(executor,
					                                   server.port(),
					                                   (byte) (65 + i));
					contexts.add(context);
					executions.add(CompletableFuture.runAsync(() -> context.execute()
					                                                       .throwIfFailed()));
				}
				Pending first = requests.poll(3,
				                              TimeUnit.SECONDS);
				Pending second = requests.poll(2,
				                               TimeUnit.SECONDS);
				Pending third = requests.poll(2,
				                              TimeUnit.SECONDS);
				Assertions.assertNotNull(first);
				Assertions.assertNotNull(second,
				                         "second stream must arrive before first response: " + contexts.stream()
				                                                                                       .map(context -> context.state() + ":" + context.failure())
				                                                                                       .toList());
				Assertions.assertNotNull(third,
				                         "third stream must arrive before any response");
				Assertions.assertEquals(first.connectionId(),
				                        second.connectionId());
				Assertions.assertEquals(first.connectionId(),
				                        third.connectionId());
				third.reply();
				second.reply();
				first.reply();
				CompletableFuture.allOf(executions.toArray(CompletableFuture[]::new))
				                 .get(4,
				                      TimeUnit.SECONDS);
				for (int i = 0; i < contexts.size(); i++) {
					Assertions.assertArrayEquals(new byte[]{(byte) (65 + i)},
					                             contexts.get(i)
					                                     .response()
					                                     .body()
					                                     .bytes(100));
				}
				Assertions.assertEquals(1,
				                        server.connections.get());
			}
			finally {
				cleanup(contexts,
				        executions);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void cancellingOneStreamDoesNotCloseSiblingOrSharedConnection(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Pending.copy(context,
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
					ExecutionContext context = context(executor,
					                                   server.port(),
					                                   value);
					contexts.add(context);
					executions.add(CompletableFuture.runAsync(context::execute));
				}
				Pending first = requests.poll(3,
				                              TimeUnit.SECONDS);
				Pending second = requests.poll(2,
				                               TimeUnit.SECONDS);
				Assertions.assertNotNull(first);
				Assertions.assertNotNull(second);
				contexts.get(0)
				        .cancel();
				executions.get(0)
				          .get(2,
				               TimeUnit.SECONDS);
				Pending cancelled = first.body()[0] == 65
				                    ? first
				                    : second;
				Assertions.assertTrue(cancelled.context()
				                               .channel()
				                               .closeFuture()
				                               .await(1500),
				                      "cancelled stream must close remotely");
				Pending sibling = first.body()[0] == 66
				                  ? first
				                  : second;
				sibling.reply();
				executions.get(1)
				          .get(2,
				               TimeUnit.SECONDS);
				contexts.get(1)
				        .throwIfFailed();
				Assertions.assertEquals(ExecutionContext.State.CANCELLED,
				                        contexts.get(0)
				                                .state());
				ExecutionContext third = context(executor,
				                                 server.port(),
				                                 (byte) 67);
				contexts.add(third);
				executions.add(CompletableFuture.runAsync(third::execute));
				Pending reused = requests.poll(2,
				                               TimeUnit.SECONDS);
				Assertions.assertNotNull(reused);
				Assertions.assertEquals(sibling.connectionId(),
				                        reused.connectionId());
				reused.reply();
				executions.get(2)
				          .get(2,
				               TimeUnit.SECONDS);
				third.throwIfFailed();
				Assertions.assertEquals(1,
				                        server.connections.get());
			}
			finally {
				cleanup(contexts,
				        executions);
			}
		}
	}

	static ExecutionContext context(HttpClientExecutor executor,
	                                int port,
	                                byte value) {
		return ExecutionContext.builder(executor)
		                       .url(URI.create("https://127.0.0.1:" + port + "/"))
		                       .body(RequestBody.bytes("application/octet-stream",
		                                               new byte[]{value}))
		                       .build();
	}

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void responseStorageLimitFailureDoesNotAbortSiblingStream(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Pending.copy(context,
		                                                                                        request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .memoryThreshold(1)
		                                                                             .maxStoredBytes(100)
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
					ExecutionContext context = context(executor,
					                                   server.port(),
					                                   value);
					contexts.add(context);
					executions.add(CompletableFuture.runAsync(context::execute));
				}
				Pending first = requests.poll(3,
				                              TimeUnit.SECONDS);
				Pending second = requests.poll(3,
				                               TimeUnit.SECONDS);
				Assertions.assertNotNull(first);
				Assertions.assertNotNull(second);
				Pending oversized = first.body()[0] == 65
				                    ? first
				                    : second;
				Pending sibling = first.body()[0] == 66
				                  ? first
				                  : second;
				DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                               HttpResponseStatus.OK,
				                                                               Unpooled.wrappedBuffer(new byte[101]));
				response.headers()
				        .set("content-length",
				             101);
				oversized.context()
				         .writeAndFlush(response)
				         .sync();
				executions.get(0)
				          .get(3,
				               TimeUnit.SECONDS);
				Assertions.assertNotNull(contexts.get(0)
				                                 .failure());
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
				Assertions.assertEquals(1,
				                        server.connections.get());
			}
			finally {
				cleanup(contexts,
				        executions);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY",
	                     "REACTOR_NETTY"})
	void excessiveInterimResponsesDoNotAbortSiblingStream(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Pending.copy(context,
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
					ExecutionContext context = context(executor,
					                                   server.port(),
					                                   value);
					contexts.add(context);
					executions.add(CompletableFuture.runAsync(context::execute));
				}
				Pending first = requests.poll(3,
				                              TimeUnit.SECONDS);
				Pending second = requests.poll(3,
				                               TimeUnit.SECONDS);
				Assertions.assertNotNull(first);
				Assertions.assertNotNull(second);
				Pending limited = first.body()[0] == 65
				                  ? first
				                  : second;
				Pending sibling = first.body()[0] == 66
				                  ? first
				                  : second;
				limited.context()
				       .executor()
				       .submit(() -> {
					       for (int i = 0; i < 17; i++) {
						       limited.context()
						              .channel()
						              .pipeline()
						              .context(io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec.class)
						              .write(new io.netty.handler.codec.http2.DefaultHttp2HeadersFrame(new io.netty.handler.codec.http2.DefaultHttp2Headers().status("103"),
						                                                                               false));
					       }
					       limited.context()
					              .flush();
				       })
				       .sync();
				executions.get(0)
				          .get(3,
				               TimeUnit.SECONDS);
				Assertions.assertNotNull(contexts.get(0)
				                                 .failure());
				Assertions.assertTrue(limited.context()
				                             .channel()
				                             .closeFuture()
				                             .await(1500),
				                      "limited stream must close remotely");
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
				ExecutionContext next = context(executor,
				                                server.port(),
				                                (byte) 67);
				contexts.add(next);
				executions.add(CompletableFuture.runAsync(next::execute));
				Pending reused = requests.poll(3,
				                               TimeUnit.SECONDS);
				Assertions.assertNotNull(reused);
				Assertions.assertEquals(sibling.connectionId(),
				                        reused.connectionId());
				reused.reply();
				executions.get(2)
				          .get(3,
				               TimeUnit.SECONDS);
				next.throwIfFailed();
				Assertions.assertEquals(1,
				                        server.connections.get());
			}
			finally {
				cleanup(contexts,
				        executions);
			}
		}
	}

	static void cleanup(List<ExecutionContext> contexts,
	                    List<CompletableFuture<Void>> executions) throws Exception {
		contexts.forEach(ExecutionContext::cancel);
		for (CompletableFuture<Void> execution : executions) {
			try {
				execution.get(4,
				              TimeUnit.SECONDS);
			}
			catch (java.util.concurrent.ExecutionException ignored) {
			}
		}
		for (ExecutionContext context : contexts) {
			context.close();
		}
	}
}
