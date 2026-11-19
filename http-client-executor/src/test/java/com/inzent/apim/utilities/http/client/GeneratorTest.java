package com.inzent.apim.utilities.http.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.payload.PayloadBody;
import com.inzent.apim.utilities.http.payload.ContentEncoding;
import com.inzent.apim.utilities.http.payload.ContentType;
import com.inzent.apim.utilities.http.payload.DefaultHttpPayloadGenerator;

class GeneratorTest {
	@Test
	void generatorStreamIsCopiedInsideCallbackAndSpillsToFile() throws Exception {
		ConnectionConfigure settings = ConnectionConfigure.builder()
		                                                  .memoryThreshold(1)
		                                                  .build();
		try (HttpClientManager manager = HttpClientManager.create(settings);
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://127.0.0.1:1/"))
		                                                .body(new PayloadBody(new DefaultHttpPayloadGenerator(),
		                                                                      false,
		                                                                      ContentType.APPLICATION_JSON,
		                                                                      ContentEncoding.IDENTITY,
		                                                                      256,
		                                                                      16))
		                                                .build()) {
			context.execute();
			Assertions.assertTrue(context.request()
			                             .body()
			                             .fileBacked());
			Assertions.assertEquals(256,
			                        context.request()
			                               .body()
			                               .size());
			Assertions.assertTrue(new String(context.request()
			                                        .body()
			                                        .bytes(300),
			                                 StandardCharsets.US_ASCII).startsWith("{"));
			Assertions.assertEquals("256",
			                        context.request()
			                               .headers()
			                               .first("Content-Length"));
		}
	}

	@Test
	void hookLimitFailsBeforeCallbackAndBeforeConnecting() throws Exception {
		AtomicBoolean called = new AtomicBoolean();
		ConnectionConfigure settings = ConnectionConfigure.builder()
		                                                  .maxInterceptorBodyBytes(10)
		                                                  .build();
		try (HttpClientManager manager = HttpClientManager.create(settings);
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://127.0.0.1:1/"))
		                                                .body(new PayloadBody(new DefaultHttpPayloadGenerator(),
		                                                                      false,
		                                                                      ContentType.APPLICATION_OCTET_STREAM,
		                                                                      ContentEncoding.IDENTITY,
		                                                                      20,
		                                                                      16))
		                                                .interceptor(new ClientHandleInterceptor() {
			                                                @Override
			                                                public byte[] contentGenerated(byte[] input) {
				                                                called.set(true);
				                                                return input;
			                                                }
		                                                })
		                                                .build()) {
			context.execute();
			Assertions.assertFalse(called.get());
			Assertions.assertNotNull(context.failure());
			Assertions.assertFalse(context.history()
			                              .stream()
			                              .anyMatch(item -> item.stage()
			                                                    .equals("ACQUIRE")));
		}
	}
}
