package com.inzent.apim.utilities.http.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

public final class HttpClientManager
		implements AutoCloseable {
	private final ConnectionConfigure defaults;
	private final List<HttpClientExecutor> executors = new ArrayList<>();
	private boolean closed;

	private HttpClientManager(ConnectionConfigure defaults) {
		this.defaults = Objects.requireNonNull(defaults);
	}

	public static HttpClientManager create(ConnectionConfigure defaults) {
		return new HttpClientManager(defaults);
	}

	public synchronized HttpClientExecutor.Builder executorBuilder(ConnectorType type) {
		if (this.closed) {
			throw new IllegalStateException("Manager closed");
		}
		return new HttpClientExecutor.Builder(this,
		                                      Objects.requireNonNull(type),
		                                      this.defaults);
	}

	synchronized void register(HttpClientExecutor executor) {
		if (this.closed) {
			executor.close();
			throw new IllegalStateException("Manager closed");
		}
		this.executors.add(executor);
	}

	@Override
	public void close() {
		List<HttpClientExecutor> snapshot;
		synchronized (this) {
			if (this.closed) {
				return;
			}
			this.executors.forEach(HttpClientExecutor::assertCanClose);
			this.closed = true;
			snapshot = List.copyOf(this.executors);
			this.executors.clear();
		}
		RuntimeException failure = null;
		for (HttpClientExecutor executor : snapshot) {
			try {
				executor.close();
			}
			catch (RuntimeException exception) {
				if (failure == null) {
					failure = exception;
				} else {
					failure.addSuppressed(exception);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	synchronized void unregister(HttpClientExecutor executor) {
		this.executors.remove(executor);
	}
}
