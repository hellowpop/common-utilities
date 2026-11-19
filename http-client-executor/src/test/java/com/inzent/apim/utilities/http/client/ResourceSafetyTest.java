package com.inzent.apim.utilities.http.client;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.store.BodyStore;

class ResourceSafetyTest {

	@Test
	void closedExecutorsAreRemovedFromLiveManager() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults())) {
			for (int i = 0; i < 100; i++) {
				manager.executorBuilder(ConnectorType.JAVA)
				       .build()
				       .close();
			}
			Assertions.assertTrue(((List<?>) field(manager,
			                                       "executors")).isEmpty(),
			                      "Closed executors must not accumulate in a long-lived manager");
		}
	}

	@Test
	void completedExecutionsDoNotRetainDeadlineTasks() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.builder()
		                                                                             .executionTimeout(Duration.ofHours(1))
		                                                                             .build());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build()) {
			for (int i = 0; i < 100; i++) {
				try (ExecutionContext context = ExecutionContext.builder(executor)
				                                                .url(URI.create("http://localhost/"))
				                                                .body(target -> {
					                                                throw new IOException("Preparation failed");
				                                                })
				                                                .build()) {
					context.execute();
					Assertions.assertInstanceOf(IOException.class,
					                            context.failure());
				}
			}
			Assertions.assertEquals(0,
			                        timer(executor).getQueue()
			                                       .size(),
			                        "Completed requests must immediately remove cancelled timer tasks");
		}
	}

	@Test
	void concurrentExecutionsShareOneInterruptWatch() throws Exception {
		int count = 12;
		CountDownLatch entered = new CountDownLatch(count);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService callers = Executors.newFixedThreadPool(count);
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build()) {
			List<Future<?>> results = new ArrayList<>();
			try {
				for (int i = 0; i < count; i++) {
					results.add(callers.submit(() -> {
						try (ExecutionContext context = ExecutionContext.builder(executor)
						                                                .url(URI.create("http://localhost/"))
						                                                .body(target -> {
							                                                entered.countDown();
							                                                try {
								                                                if (!release.await(5,
								                                                                   TimeUnit.SECONDS)) {
									                                                throw new IOException("Fixture timeout");
								                                                }
							                                                }
							                                                catch (InterruptedException failure) {
								                                                Thread.currentThread()
								                                                      .interrupt();
								                                                throw new IOException(failure);
							                                                }
							                                                throw new IOException("Preparation failed");
						                                                })
						                                                .build()) {
							context.execute();
						}
						catch (IOException failure) {
							throw new RuntimeException(failure);
						}
					}));
				}
				Assertions.assertTrue(entered.await(5,
				                                    TimeUnit.SECONDS));
				Assertions.assertTrue(timer(executor).getQueue()
				                                     .size() <= count + 1,
				                      "At most one interrupt watch plus one deadline per active request");
			}
			finally {
				release.countDown();
				for (Future<?> result : results) {
					result.get(5,
					           TimeUnit.SECONDS);
				}
			}
		}
		finally {
			callers.shutdownNow();
		}
	}

	@Test
	void nullBodyHeadersAreReportedAsContractFailure() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.JAVA)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://localhost/"))
		                                                .body(target -> null)
		                                                .build()) {
			context.execute();
			Assertions.assertInstanceOf(IOException.class,
			                            context.failure());
			Assertions.assertTrue(context.failure()
			                             .getMessage()
			                             .contains("RequestBody.prepare"));
		}
	}

	@Test
	void fileIsDeletedEvenWhenOutputCloseFails(
			@TempDir Path directory) throws Exception {
		BodyStore store = new BodyStore(ConnectionConfigure.builder()
		                                                   .memoryThreshold(0)
		                                                   .temporaryDirectory(directory)
		                                                   .build());
		store.write(new byte[]{1,
		                       2,
		                       3});
		OutputStream actual = (OutputStream) field(store,
		                                           "output");
		Field output = BodyStore.class.getDeclaredField("output");
		output.setAccessible(true);
		output.set(store,
		           new OutputStream() {
			           @Override
			           public void write(int value) throws IOException {
				           actual.write(value);
			           }

			           @Override
			           public void close() throws IOException {
				           actual.close();
				           throw new IOException("Injected close failure");
			           }
		           });
		Assertions.assertThrows(IOException.class,
		                        store::close);
		try (java.util.stream.Stream<Path> files = Files.list(directory)) {
			Assertions.assertEquals(0,
			                        files.count(),
			                        "Seal failure must not skip temporary file deletion");
		}
		Assertions.assertThrows(IOException.class,
		                        () -> store.write(4));
		store.close();
	}

	private static Object field(Object target,
	                            String name) throws Exception {
		Field field = target.getClass()
		                    .getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static ScheduledThreadPoolExecutor timer(HttpClientExecutor executor) throws Exception {
		return (ScheduledThreadPoolExecutor) field(executor,
		                                           "timer");
	}
}
