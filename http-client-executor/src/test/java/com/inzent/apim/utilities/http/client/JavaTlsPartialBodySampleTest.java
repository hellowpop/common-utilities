package com.inzent.apim.utilities.http.client;

import java.io.EOFException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
import com.inzent.apim.utilities.http.client.model.ConnectMethod;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.RequestBody;
import com.inzent.apim.utilities.http.client.model.WireMode;
import com.inzent.apim.utilities.http.client.spi.Http1Protocol;

class JavaTlsPartialBodySampleTest {
	@Test
	void waitsForServerClose(
			@TempDir Path directory) throws Exception {
		SSLContext tls = TlsFixture.localContext(directory);
		// The local server supplies its public certificate for this example.
		Path serverCertificate = directory.resolve("server.cer");
		Files.write(serverCertificate,
		            TlsFixture.loadKeys(directory)
		                      .getCertificate("fixture")
		                      .getEncoded());
		try (ServerSocket listener = tls.getServerSocketFactory()
		                                .createServerSocket(0,
		                                                    1,
		                                                    InetAddress.getByName("127.0.0.1"));
		     HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .sslContext(clientTls(serverCertificate))
		                                                                             .responseIdleTimeout(Duration.ofSeconds(5))
		                                                                             .executionTimeout(Duration.ofSeconds(10))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("https://127.0.0.1:" + listener.getLocalPort() + "/"))
		                                                .method(ConnectMethod.POST)
		                                                .wireMode(WireMode.RAW_HTTP1)
		                                                .body(RequestBody.bytes("text/plain",
		                                                                        "ABCDEFGH".getBytes(StandardCharsets.US_ASCII)))
		                                                .interceptor(new ClientHandleInterceptor() {
			                                                @Override
			                                                public byte[] contentGenerated(byte[] content) {
				                                                return Arrays.copyOf(content,
				                                                                     3);
			                                                }
		                                                })
		                                                .shutdownOutput(false)
		                                                .build()) {
			FutureTask<Void> server = new FutureTask<>(() -> {
				receivePrefixAndClose(listener);
				return null;
			});
			Thread thread = new Thread(server,
			                           "partial-body-tls-server");
			thread.setDaemon(true);
			thread.start();

			context.execute();
			if (!(context.failure() instanceof EOFException)) {
				context.throwIfFailed();
			}
			server.get(5,
			           TimeUnit.SECONDS);
			Assertions.assertInstanceOf(EOFException.class,
			                            context.failure());
		}
	}

	private static SSLContext clientTls(Path serverCertificate) throws Exception {
		KeyStore trusted = KeyStore.getInstance("PKCS12");
		trusted.load(null,
		             null);
		try (InputStream input = Files.newInputStream(serverCertificate)) {
			trusted.setCertificateEntry("service",
			                            CertificateFactory.getInstance("X.509")
			                                              .generateCertificate(input));
		}
		TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trust.init(trusted);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null,
		             trust.getTrustManagers(),
		             null);
		return context;
	}

	private static void receivePrefixAndClose(ServerSocket listener) throws Exception {
		listener.setSoTimeout(5000);
		try (SSLSocket socket = (SSLSocket) listener.accept()) {
			socket.setSoTimeout(5000);
			InputStream input = socket.getInputStream();
			Http1Protocol.line(input,
			                   8192);
			String length = null;
			for (String line; !(line = Http1Protocol.line(input,
			                                              8192)).isEmpty(); ) {
				String[] header = line.split(":",
				                             2);
				if ("Content-Length".equalsIgnoreCase(header[0])) {
					length = header[1].trim();
				}
			}
			Assertions.assertEquals("8",
			                        length);
			Assertions.assertArrayEquals("ABC".getBytes(StandardCharsets.US_ASCII),
			                             input.readNBytes(3));
			// An idle read confirms the client left its output open after the prefix.
			socket.setSoTimeout(250);
			Assertions.assertThrows(SocketTimeoutException.class,
			                        input::read);
		}
	}
}
