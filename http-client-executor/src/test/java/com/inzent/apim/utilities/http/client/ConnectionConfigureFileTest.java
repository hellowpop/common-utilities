package com.inzent.apim.utilities.http.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class ConnectionConfigureFileTest {

	@TempDir
	Path directory;

	@Test
	void jsonUsesBuilderDefaultsAndCreatesAnExecutor() throws IOException {
		Path file = Files.writeString(this.directory.resolve("connection.json"),
		                              """
				                              {
				                                "connectTimeout": "PT2.5S",
				                                "tcpNoDelay": false,
				                                "maxConnTotal": 30,
				                                "tlsProtocols": ["TLSv1.3"],
				                                "temporaryDirectory": "response-bodies",
				                                "proxy": {"host": "localhost", "port": 8080}
				                              }
				                              """);

		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.load(file));
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.REACTOR_NETTY)
		                                          .build()) {
			ConnectionConfigure settings = executor.configure();
			Assertions.assertEquals(Duration.ofMillis(2500),
			                        settings.connectTimeout());
			Assertions.assertEquals(30,
			                        settings.maxConnTotal());
			Assertions.assertEquals(20,
			                        settings.maxConnPerRoute());
			Assertions.assertFalse(settings.tcpNoDelay());
			Assertions.assertTrue(settings.keepAlive());
			Assertions.assertEquals(List.of("TLSv1.3"),
			                        settings.tlsProtocols());
			Assertions.assertEquals(Path.of("response-bodies"),
			                        settings.temporaryDirectory());
			Assertions.assertEquals(8080,
			                        settings.proxy()
			                                .port());
			Assertions.assertNull(settings.proxy()
			                              .authorization());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"yml",
	                        "YAML"})
	void yamlReadsConnectionSettingsAndProxyCredentials(String extension) throws IOException {
		Path file = Files.writeString(this.directory.resolve("connection." + extension),
		                              """
				                              maxConnTotal: 12
				                              maxConnPerRoute: 4
				                              maxConcurrentStreamsPerConnection: 8
				                              responseIdleTimeout: PT12S
				                              localAddress: 127.0.0.1
				                              leaseOrder: FIFO
				                              maxStoredBytes: 3221225472
				                              proxy:
				                                host: proxy.example
				                                port: 3128
				                                user: alice
				                                password: "1234"
				                              """);

		ConnectionConfigure settings = ConnectionConfigure.load(file);
		Assertions.assertEquals(12,
		                        settings.maxConnTotal());
		Assertions.assertEquals(4,
		                        settings.maxConnPerRoute());
		Assertions.assertEquals(8,
		                        settings.maxConcurrentStreamsPerConnection());
		Assertions.assertEquals(Duration.ofSeconds(12),
		                        settings.responseIdleTimeout());
		Assertions.assertEquals("127.0.0.1",
		                        settings.localAddress()
		                                .getHostAddress());
		Assertions.assertEquals(ConnectionConfigure.LeaseOrder.FIFO,
		                        settings.leaseOrder());
		Assertions.assertEquals(3221225472L,
		                        settings.maxStoredBytes());
		Assertions.assertEquals("proxy.example",
		                        settings.proxy()
		                                .host());
		Assertions.assertEquals(3128,
		                        settings.proxy()
		                                .port());
		Assertions.assertEquals("Basic YWxpY2U6MTIzNA==",
		                        settings.proxy()
		                                .authorization());
	}

	@ParameterizedTest
	@ValueSource(strings = {"null",
	                        "[]",
	                        "{\"workerThreds\": 3}",
	                        "{\"sslContext\": {}}",
	                        "{\"workerThreads\": 1, \"workerThreads\": 2}",
	                        "{} {}",
	                        "{\"workerThreads\": null}",
	                        "{\"workerThreads\": 1.5}",
	                        "{\"connectTimeout\": 5}",
	                        "{\"connectTimeout\": \"PT0S\"}",
	                        "{\"maxConnTotal\": 10}",
	                        "{\"proxy\": {\"host\": \"localhost\", \"port\": 3128, \"password\": \"secret\"}}"})
	void invalidJsonFailsInsteadOfReturningPartialSettings(String content) throws IOException {
		Path file = Files.writeString(this.directory.resolve("invalid.json"),
		                              content);
		Assertions.assertThrows(IOException.class,
		                        () -> ConnectionConfigure.load(file));
	}

	@ParameterizedTest
	@ValueSource(strings = {"workerThreads: 1\nworkerThreads: 2\n",
	                        "workerThreads: 1\n---\nworkerThreads: 2\n"})
	void duplicateSettingsAndExtraYamlDocumentsAreRejected(String content) throws IOException {
		Path file = Files.writeString(this.directory.resolve("invalid.yaml"),
		                              content);
		Assertions.assertThrows(IOException.class,
		                        () -> ConnectionConfigure.load(file));
	}

	@Test
	void unsupportedExtensionAndMissingFileAreRejected() throws IOException {
		Path unsupported = Files.writeString(this.directory.resolve("connection.txt"),
		                                     "{}");
		Assertions.assertThrows(IOException.class,
		                        () -> ConnectionConfigure.load(unsupported));
		Assertions.assertThrows(IOException.class,
		                        () -> ConnectionConfigure.load(this.directory.resolve("missing.json")));
	}
}
