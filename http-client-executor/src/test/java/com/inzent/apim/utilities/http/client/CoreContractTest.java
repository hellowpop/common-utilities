package com.inzent.apim.utilities.http.client;

import java.net.URI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectMethod;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.HttpHeaders;

class CoreContractTest {
	@Test
	void snapshotOverridesAndEncodedUriArePreserved() throws Exception {
		ConnectionConfigure defaults = ConnectionConfigure.builder()
		                                                  .maxConnTotal(9)
		                                                  .maxConnPerRoute(3)
		                                                  .build();
		try (HttpClientManager manager = HttpClientManager.create(defaults);
		     HttpClientExecutor first = manager.executorBuilder(ConnectorType.JAVA)
		                                       .location(URI.create("http://localhost:8080/api/"))
		                                       .path("/a%2Fb?q=x%20y")
		                                       .maxConnTotal(7)
		                                       .build();
		     HttpClientExecutor second = manager.executorBuilder(ConnectorType.JAVA)
		                                        .build();
		     ExecutionContext context = ExecutionContext.builder(first)
		                                                .build()) {
			Assertions.assertEquals(URI.create("http://localhost:8080/api/a%2Fb?q=x%20y"),
			                        context.url());
			Assertions.assertEquals(7,
			                        first.configure()
			                             .maxConnTotal());
			Assertions.assertEquals(9,
			                        second.configure()
			                              .maxConnTotal());
		}
	}

	@Test
	void headersKeepDuplicateValuesAndRejectInjection() {
		HttpHeaders headers = HttpHeaders.builder()
		                                 .add("X-A",
		                                      "one")
		                                 .add("x-a",
		                                      "two")
		                                 .build();
		Assertions.assertEquals(java.util.List.of("one",
		                                          "two"),
		                        headers.values("X-A"));
		Assertions.assertThrows(IllegalArgumentException.class,
		                        () -> HttpHeaders.builder()
		                                         .add("X",
		                                              "a\r\nb"));
		Assertions.assertThrows(IllegalArgumentException.class,
		                        () -> ConnectMethod.of("BAD METHOD"));
	}

	@Test
	void contextIsSingleUseAndFailureIsRecorded() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://127.0.0.1:1/"))
		                                                .build()) {
			Assertions.assertSame(context,
			                      context.execute());
			Assertions.assertEquals(ExecutionContext.State.FAILED,
			                        context.state());
			Assertions.assertNotNull(context.failure());
			Assertions.assertFalse(context.history()
			                              .isEmpty());
			Assertions.assertThrows(IllegalStateException.class,
			                        context::execute);
		}
	}

	@Test
	void rejectsUrlAndPathAndInvalidPoolLimits() throws Exception {
		Assertions.assertThrows(IllegalArgumentException.class,
		                        () -> ConnectionConfigure.builder()
		                                                 .maxConnTotal(1)
		                                                 .maxConnPerRoute(2)
		                                                 .build());
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build()) {
			Assertions.assertThrows(IllegalArgumentException.class,
			                        () -> ExecutionContext.builder(executor)
			                                              .url(URI.create("http://localhost/"))
			                                              .path("x")
			                                              .build());
		}
	}

	@Test
	void rejectsExecutorCloseFromOwnCallback() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .shutdownTimeout(java.time.Duration.ofMillis(20))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://localhost:1/"))
		                                                .interceptor(new com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor() {
			                                                @Override
			                                                public byte[] contentGenerated(byte[] bytes) {
				                                                Assertions.assertThrows(IllegalStateException.class,
				                                                                        executor::close);
				                                                return null;
			                                                }
		                                                })
		                                                .build()) {
			context.execute();
			Assertions.assertNotNull(context.failure());
		}
	}
}
