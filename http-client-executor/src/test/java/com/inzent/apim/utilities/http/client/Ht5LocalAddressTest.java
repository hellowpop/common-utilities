package com.inzent.apim.utilities.http.client;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class Ht5LocalAddressTest {
	@TempDir
	Path directory;

	@Test
	void invalidLocalAddressFailsBeforeSendingToOrigin() throws Exception {
		try (TlsFixture server = new TlsFixture(this.directory,
		                                        true,
		                                        true);
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .localAddress(InetAddress.getByName("203.0.113.7"))
		                                                                             .executionTimeout(java.time.Duration.ofSeconds(3))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.HT5)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build();
		     ExecutionContext context = Http2MultiplexTest.context(executor,
		                                                           server.port(),
		                                                           (byte) 65)) {
			context.execute();
			Assertions.assertNotNull(context.failure());
			Assertions.assertFalse(context.requestSent());
			Assertions.assertEquals(0,
			                        server.connections.get());
		}
	}

	@Test
	void directHttp2UsesConfiguredLocalAddress() throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(this.directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .localAddress(InetAddress.getByName("127.0.0.2"))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.HT5)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build();
		     ExecutionContext context = Http2MultiplexTest.context(executor,
		                                                           server.port(),
		                                                           (byte) 65)) {
			CompletableFuture<Void> execution = CompletableFuture.runAsync(context::execute);
			try {
				Http2MultiplexTest.Pending received = requests.poll(3,
				                                                    TimeUnit.SECONDS);
				Assertions.assertNotNull(received,
				                         () -> context.state() + ": " + context.failure());
				Assertions.assertEquals("127.0.0.2",
				                        received.remoteHost());
				received.reply();
				execution.get(3,
				              TimeUnit.SECONDS);
				context.throwIfFailed();
			}
			finally {
				Http2MultiplexTest.cleanup(List.of(context),
				                           List.of(execution));
			}
		}
	}
}
