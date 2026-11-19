package com.inzent.apim.utilities.http.client;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class Ht5GoAwayRaceTest {

	@TempDir
	Path directory;

	@Test
	void goAwayBetweenAcquisitionAndSubmissionPreservesDrainingSibling() throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(this.directory,
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
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.HT5)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build()) {
			List<ExecutionContext> contexts = new ArrayList<>();
			List<CompletableFuture<Void>> executions = new ArrayList<>();
			try {
				ExecutionContext sibling = Http2MultiplexTest.context(executor,
				                                                      server.port(),
				                                                      (byte) 65);
				contexts.add(sibling);
				executions.add(CompletableFuture.runAsync(sibling::execute));
				Http2MultiplexTest.Pending active = requests.poll(3,
				                                                  TimeUnit.SECONDS);
				Assertions.assertNotNull(active);

				Object pool = field(executor,
				                    "pool");
				List<?> entries = (List<?>) field(pool,
				                                  "entries");
				Object transport = field(entries.get(0),
				                         "connection");
				Object registry = field(transport,
				                        "connections");
				@SuppressWarnings("unchecked")
				List<ManagedAsyncClientConnection> connections = (List<ManagedAsyncClientConnection>) field(registry,
				                                                                                            "connections");
				ManagedAsyncClientConnection original = connections.get(0);
				AtomicBoolean triggered = new AtomicBoolean();
				ManagedAsyncClientConnection decorated = (ManagedAsyncClientConnection) Proxy.newProxyInstance(ManagedAsyncClientConnection.class.getClassLoader(),
				                                                                                               new Class<?>[]{ManagedAsyncClientConnection.class},
				                                                                                               (proxy, method, arguments) -> {
					                                                                                               if (method.getName()
					                                                                                                         .equals("getSSLSession") && triggered.compareAndSet(false,
					                                                                                                                                                             true)) {
						                                                                                               // Registry lookup runs after acquisition. Force GOAWAY into precisely that window.
						                                                                                               active.context()
						                                                                                                     .channel()
						                                                                                                     .parent()
						                                                                                                     .writeAndFlush(new io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame(io.netty.handler.codec.http2.Http2Error.NO_ERROR))
						                                                                                                     .sync();
						                                                                                               long deadline = System.nanoTime() + Duration.ofSeconds(2)
						                                                                                                                                           .toNanos();
						                                                                                               while (original.isOpen() && System.nanoTime() < deadline) {
							                                                                                               Thread.sleep(1);
						                                                                                               }
						                                                                                               Assertions.assertFalse(original.isOpen(),
						                                                                                                                      "GOAWAY must be observed before registry lookup continues");
					                                                                                               }
					                                                                                               try {
						                                                                                               return method.invoke(original,
						                                                                                                                    arguments);
					                                                                                               }
					                                                                                               catch (InvocationTargetException failure) {
						                                                                                               throw failure.getCause();
					                                                                                               }
				                                                                                               });
				connections.set(0,
				                decorated);
				ExecutionContext next = Http2MultiplexTest.context(executor,
				                                                   server.port(),
				                                                   (byte) 66);
				contexts.add(next);
				executions.add(CompletableFuture.runAsync(next::execute));
				executions.get(1)
				          .get(3,
				               TimeUnit.SECONDS);
				Assertions.assertTrue(triggered.get(),
				                      "test must exercise the acquisition/submission window");
				Assertions.assertNotNull(next.failure());
				Assertions.assertTrue(next.failure()
				                          .toString()
				                          .contains("Apache HTTP/2 transport unavailable"),
				                      () -> "unexpected failure: " + next.failure());
				active.reply();
				executions.get(0)
				          .get(3,
				               TimeUnit.SECONDS);
				Assertions.assertNull(sibling.failure(),
				                      () -> "draining sibling failed: " + sibling.failure());
				Assertions.assertArrayEquals(new byte[]{65},
				                             sibling.response()
				                                    .body()
				                                    .bytes(100));
			}
			finally {
				Http2MultiplexTest.cleanup(contexts,
				                           executions);
			}
		}
	}

	private static Object field(Object owner,
	                            String name) throws Exception {
		Field field = owner.getClass()
		                   .getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}
}
