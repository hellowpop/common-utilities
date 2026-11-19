package com.inzent.apim.utilities.http.client;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class Http2ProxyTest {
	@TempDir
	static Path directory;

	@ParameterizedTest
	@EnumSource(value = ConnectorType.class,
	            names = {"HT5",
	                     "NETTY"})
	void authenticatedTunnelMultiplexesAndBindsLocalAddressWithoutLeakingProxyCredentials(ConnectorType type) throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     TunnelProxy proxy = new TunnelProxy(server.port(),
		                                         "Basic dXNlcjpwYXNz");
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .proxy(ProxyConfigure.basic("127.0.0.1",
		                                                                                                         proxy.port(),
		                                                                                                         "user",
		                                                                                                         "pass"))
		                                                                             .localAddress(InetAddress.getByName("127.0.0.2"))
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
				Assertions.assertEquals(first.connectionId(),
				                        second.connectionId());
				Assertions.assertNull(first.proxyAuthorization());
				Assertions.assertNull(second.proxyAuthorization());
				second.reply();
				first.reply();
				CompletableFuture.allOf(executions.toArray(CompletableFuture[]::new))
				                 .get(3,
				                      TimeUnit.SECONDS);
				contexts.forEach(ExecutionContext::throwIfFailed);
				TunnelProxy.Seen connect = proxy.requests.poll(1,
				                                               TimeUnit.SECONDS);
				Assertions.assertNotNull(connect);
				Assertions.assertEquals("127.0.0.2",
				                        connect.remoteHost());
				Assertions.assertTrue(connect.headers()
				                             .startsWith("CONNECT 127.0.0.1:" + server.port() + " HTTP/1.1\r\n"));
				Assertions.assertEquals(1,
				                        proxy.connections.get());
				Assertions.assertEquals(1,
				                        server.connections.get());
			}
			finally {
				Http2MultiplexTest.cleanup(contexts,
				                           executions);
			}
		}
	}

	@Test
	void ht5RejectedProxyAuthenticationReportsConnectFailureWithoutOriginRequest() throws Exception {
		LinkedBlockingQueue<Http2MultiplexTest.Pending> requests = new LinkedBlockingQueue<>();
		List<Boolean> events = new ArrayList<>();
		try (TlsFixture server = new TlsFixture(directory,
		                                        true,
		                                        true,
		                                        10,
		                                        (context, request) -> requests.add(Http2MultiplexTest.Pending.copy(context,
		                                                                                                           request)));
		     TunnelProxy proxy = new TunnelProxy(server.port(),
		                                         "Basic dXNlcjpwYXNz");
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(server.client)
		                                                                             .proxy(ProxyConfigure.basic("127.0.0.1",
		                                                                                                         proxy.port(),
		                                                                                                         "user",
		                                                                                                         "wrong"))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.HT5)
		                                          .version(ConnectVersion.HTTP_2)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(java.net.URI.create("https://127.0.0.1:" + server.port()))
		                                                .interceptor(new ClientHandleInterceptor() {
			                                                @Override
			                                                public void connectResult(ConnectResult result) {
				                                                events.add(result.success());
			                                                }
		                                                })
		                                                .build()) {
			context.execute();
			Assertions.assertNotNull(context.failure());
			Assertions.assertEquals(List.of(false),
			                        events);
			Assertions.assertEquals(0,
			                        server.connections.get());
			Assertions.assertTrue(requests.isEmpty());
		}
	}
}
