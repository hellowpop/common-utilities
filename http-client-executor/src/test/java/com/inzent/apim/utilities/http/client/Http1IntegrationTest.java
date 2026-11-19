package com.inzent.apim.utilities.http.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
import com.inzent.apim.utilities.http.client.model.CaptureLevel;
import com.inzent.apim.utilities.http.client.model.ConnectMethod;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;
import com.inzent.apim.utilities.http.client.model.RequestBody;

class Http1IntegrationTest {
	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void changedBodyIsNotTruncatedAndCaptureMatchesActualWire(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
				try (Socket socket = server.accept()) {
					socket.setSoTimeout(3000);
					byte[] headers = readHeaders(socket.getInputStream());
					byte[] body = socket.getInputStream()
					                    .readNBytes(5);
					ByteArrayOutputStream bytes = new ByteArrayOutputStream();
					bytes.write(headers);
					bytes.write(body);
					socket.getOutputStream()
					      .write("HTTP/1.1 422 Unprocessable\r\nContent-Length: 3\r\nConnection: close\r\n\r\nerr".getBytes(StandardCharsets.ISO_8859_1));
					return bytes.toByteArray();
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			List<String> events = new ArrayList<>();
			try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .location(URI.create("http://localhost:" + server.getLocalPort()))
			                                          .build();
			     ExecutionContext context = ExecutionContext.builder(executor)
			                                                .path("/probe")
			                                                .method(ConnectMethod.POST)
			                                                .body(RequestBody.bytes("text/plain",
			                                                                        new byte[]{65,
			                                                                                   66}))
			                                                .captureLevel(CaptureLevel.HTTP1_PLAINTEXT_BYTES)
			                                                .interceptor(new ClientHandleInterceptor() {
				                                                @Override
				                                                public byte[] contentGenerated(byte[] input) {
					                                                events.add("content");
					                                                return "ABCDE".getBytes(StandardCharsets.US_ASCII);
				                                                }

				                                                @Override
				                                                public void connectResult(ConnectResult result) {
					                                                events.add("connect:" + result.success());
				                                                }

				                                                @Override
				                                                public void requestSent(long bytes) {
					                                                events.add("sent:" + bytes);
				                                                }

				                                                @Override
				                                                public void responseReceived(HttpResponsePayload response) {
					                                                events.add("response");
				                                                }
			                                                })
			                                                .build()) {
				context.execute()
				       .throwIfFailed();
				Assertions.assertEquals(422,
				                        context.response()
				                               .status()
				                               .code());
				Assertions.assertArrayEquals("err".getBytes(StandardCharsets.US_ASCII),
				                             context.response()
				                                    .body()
				                                    .bytes(100));
				byte[] wire = received.get(5,
				                           TimeUnit.SECONDS);
				Assertions.assertTrue(new String(wire,
				                                 StandardCharsets.ISO_8859_1).contains("Content-Length: 2\r\n"));
				Assertions.assertTrue(new String(wire,
				                                 StandardCharsets.ISO_8859_1).endsWith("ABCDE"));
				Assertions.assertArrayEquals(wire,
				                             context.requestCapture()
				                                    .bytes(10000));
				Assertions.assertEquals(List.of("content",
				                                "connect:true",
				                                "sent:5",
				                                "response"),
				                        events);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(ConnectorType.class)
	void parsesChunkedTrailersAndReusesConnection(ConnectorType type) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
				try (Socket socket = server.accept()) {
					socket.setSoTimeout(3000);
					for (int i = 0; i < 2; i++) {
						readHeaders(socket.getInputStream());
						socket.getOutputStream()
						      .write("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\nX-Trailer: yes\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
						socket.getOutputStream()
						      .flush();
					}
				}
				catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			List<Boolean> reuse = new ArrayList<>();
			try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
			     HttpClientExecutor executor = manager.executorBuilder(type)
			                                          .location(URI.create("http://localhost:" + server.getLocalPort()))
			                                          .build()) {
				for (int i = 0; i < 2; i++) {
					try (ExecutionContext context = ExecutionContext.builder(executor)
					                                                .build()) {
						context.execute()
						       .throwIfFailed();
						Assertions.assertEquals("abc",
						                        new String(context.response()
						                                          .body()
						                                          .bytes(100),
						                                   StandardCharsets.US_ASCII));
						Assertions.assertEquals("yes",
						                        context.response()
						                               .trailers()
						                               .first("X-Trailer"));
						Assertions.assertEquals(100,
						                        context.response()
						                               .interim()
						                               .get(0)
						                               .code());
						reuse.add(context.history()
						                 .stream()
						                 .anyMatch(item -> item.outcome()
						                                       .equals("REUSED")));
					}
				}
			}
			Assertions.assertEquals(List.of(false,
			                                true),
			                        reuse);
			served.get(5,
			           TimeUnit.SECONDS);
		}
	}

	static byte[] readHeaders(InputStream input) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int state = 0;
		while (state < 4) {
			int value = input.read();
			if (value < 0) {
				throw new java.io.EOFException();
			}
			output.write(value);
			state = value == (state % 2 == 0
			                  ? '\r'
			                  : '\n')
			        ? state + 1
			        : (value == '\r'
			           ? 1
			           : 0);
		}
		return output.toByteArray();
	}
}
