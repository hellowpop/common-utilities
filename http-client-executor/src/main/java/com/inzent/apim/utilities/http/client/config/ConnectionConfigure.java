package com.inzent.apim.utilities.http.client.config;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLContext;

/**
 * Immutable per-executor connection settings; SSLContext must not be mutated after injection.
 */
public final class ConnectionConfigure {
	public enum LeaseOrder {
		FIFO,
		LIFO
	}

	private final Builder values;

	private ConnectionConfigure(Builder values) {
		this.values = new Builder(values);
		if (values.maxConnTotal < 1 || values.maxConnPerRoute < 1 || values.maxConnPerRoute > values.maxConnTotal) {
			throw new IllegalArgumentException("Invalid connection limits");
		}
		if (values.maxStoredBytes < 1 || values.maxInterceptorBodyBytes < 1 || values.memoryThreshold < 0 || values.memoryThreshold > values.maxStoredBytes || values.maxInterceptorBodyBytes > Integer.MAX_VALUE - 8L) {
			throw new IllegalArgumentException("Invalid body storage limits");
		}
		if (values.sendBufferSize < 0 || values.receiveBufferSize < 0 || values.workerThreads < 1) {
			throw new IllegalArgumentException("Invalid TCP/worker setting");
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ConnectionConfigure defaults() {
		return builder().build();
	}

	/**
	 * Loads settings from a JSON, YML or YAML file. Omitted settings use the builder defaults.
	 *
	 * @throws IOException if the file cannot be read or its settings are invalid
	 */
	public static ConnectionConfigure load(Path file) throws IOException {
		return ConnectionConfigureFile.load(file);
	}

	public Builder toBuilder() {
		return new Builder(this.values);
	}

	public int maxConnTotal() {
		return this.values.maxConnTotal;
	}

	public int maxConnPerRoute() {
		return this.values.maxConnPerRoute;
	}

	public int maxConcurrentStreamsPerConnection() {
		return this.values.maxConcurrentStreamsPerConnection;
	}

	public Duration connectTimeout() {
		return this.values.connectTimeout;
	}

	public Duration poolAcquireTimeout() {
		return this.values.poolAcquireTimeout;
	}

	public Duration tlsHandshakeTimeout() {
		return this.values.tlsHandshakeTimeout;
	}

	public Duration responseIdleTimeout() {
		return this.values.responseIdleTimeout;
	}

	public Duration executionTimeout() {
		return this.values.executionTimeout;
	}

	public Duration idleTimeout() {
		return this.values.idleTimeout;
	}

	public Duration timeToLive() {
		return this.values.timeToLive;
	}

	public Duration shutdownTimeout() {
		return this.values.shutdownTimeout;
	}

	public boolean tcpNoDelay() {
		return this.values.tcpNoDelay;
	}

	public boolean keepAlive() {
		return this.values.keepAlive;
	}

	public int sendBufferSize() {
		return this.values.sendBufferSize;
	}

	public int receiveBufferSize() {
		return this.values.receiveBufferSize;
	}

	public InetAddress localAddress() {
		return this.values.localAddress;
	}

	public SSLContext sslContext() {
		return this.values.sslContext;
	}

	public List<String> tlsProtocols() {
		return this.values.tlsProtocols;
	}

	public ProxyConfigure proxy() {
		return this.values.proxy;
	}

	public LeaseOrder leaseOrder() {
		return this.values.leaseOrder;
	}

	public long maxStoredBytes() {
		return this.values.maxStoredBytes;
	}

	public long maxInterceptorBodyBytes() {
		return this.values.maxInterceptorBodyBytes;
	}

	public int memoryThreshold() {
		return this.values.memoryThreshold;
	}

	public Path temporaryDirectory() {
		return this.values.temporaryDirectory;
	}

	public int workerThreads() {
		return this.values.workerThreads;
	}

	public int readBufferSize() {
		return this.values.readBufferSize;
	}

	public static final class Builder {
		private int maxConnTotal = 100;
		private int maxConnPerRoute = 20;
		private int maxConcurrentStreamsPerConnection = 100;
		private Duration connectTimeout = Duration.ofSeconds(5);
		private Duration poolAcquireTimeout = Duration.ofSeconds(5);
		private Duration tlsHandshakeTimeout = Duration.ofSeconds(10);
		private Duration responseIdleTimeout = Duration.ofSeconds(30);
		private Duration executionTimeout = Duration.ofSeconds(60);
		private Duration idleTimeout = Duration.ofSeconds(30);
		private Duration timeToLive = Duration.ofMinutes(5);
		private Duration shutdownTimeout = Duration.ofSeconds(5);
		private boolean tcpNoDelay = true;
		private boolean keepAlive = true;
		private int sendBufferSize;
		private int receiveBufferSize;
		private InetAddress localAddress;
		private SSLContext sslContext;
		private List<String> tlsProtocols = List.of("TLSv1.3",
		                                            "TLSv1.2");
		private ProxyConfigure proxy;
		private LeaseOrder leaseOrder = LeaseOrder.LIFO;
		private long maxStoredBytes = 64L * 1024 * 1024;
		private long maxInterceptorBodyBytes = 16L * 1024 * 1024;
		private int memoryThreshold = 1024 * 1024;
		private Path temporaryDirectory;
		private int workerThreads = 2;
		private int readBufferSize = 8192;

		private Builder() {
		}

		private Builder(Builder source) {
			this.maxConnTotal = source.maxConnTotal;
			this.maxConnPerRoute = source.maxConnPerRoute;
			this.maxConcurrentStreamsPerConnection = source.maxConcurrentStreamsPerConnection;
			this.connectTimeout = source.connectTimeout;
			this.poolAcquireTimeout = source.poolAcquireTimeout;
			this.tlsHandshakeTimeout = source.tlsHandshakeTimeout;
			this.responseIdleTimeout = source.responseIdleTimeout;
			this.executionTimeout = source.executionTimeout;
			this.idleTimeout = source.idleTimeout;
			this.timeToLive = source.timeToLive;
			this.shutdownTimeout = source.shutdownTimeout;
			this.tcpNoDelay = source.tcpNoDelay;
			this.keepAlive = source.keepAlive;
			this.sendBufferSize = source.sendBufferSize;
			this.receiveBufferSize = source.receiveBufferSize;
			this.localAddress = source.localAddress;
			this.sslContext = source.sslContext;
			this.tlsProtocols = source.tlsProtocols;
			this.proxy = source.proxy;
			this.leaseOrder = source.leaseOrder;
			this.maxStoredBytes = source.maxStoredBytes;
			this.maxInterceptorBodyBytes = source.maxInterceptorBodyBytes;
			this.memoryThreshold = source.memoryThreshold;
			this.temporaryDirectory = source.temporaryDirectory;
			this.workerThreads = source.workerThreads;
			this.readBufferSize = source.readBufferSize;
		}

		private static Duration positive(Duration value) {
			Objects.requireNonNull(value,
			                       "duration");
			if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(365)) > 0) {
				throw new IllegalArgumentException("Duration must be positive and at most 365 days");
			}
			return value;
		}

		public Builder maxConnTotal(int value) {
			this.maxConnTotal = value;
			return this;
		}

		public Builder maxConnPerRoute(int value) {
			this.maxConnPerRoute = value;
			return this;
		}

		public Builder maxConcurrentStreamsPerConnection(int value) {
			if (value < 1) {
				throw new IllegalArgumentException("Positive stream limit required");
			}
			this.maxConcurrentStreamsPerConnection = value;
			return this;
		}

		public Builder connectTimeout(Duration value) {
			this.connectTimeout = positive(value);
			return this;
		}

		public Builder poolAcquireTimeout(Duration value) {
			this.poolAcquireTimeout = positive(value);
			return this;
		}

		public Builder tlsHandshakeTimeout(Duration value) {
			this.tlsHandshakeTimeout = positive(value);
			return this;
		}

		public Builder responseIdleTimeout(Duration value) {
			this.responseIdleTimeout = positive(value);
			return this;
		}

		public Builder executionTimeout(Duration value) {
			this.executionTimeout = positive(value);
			return this;
		}

		public Builder idleTimeout(Duration value) {
			this.idleTimeout = positive(value);
			return this;
		}

		public Builder timeToLive(Duration value) {
			this.timeToLive = positive(value);
			return this;
		}

		public Builder shutdownTimeout(Duration value) {
			this.shutdownTimeout = positive(value);
			return this;
		}

		public Builder tcpNoDelay(boolean value) {
			this.tcpNoDelay = value;
			return this;
		}

		public Builder keepAlive(boolean value) {
			this.keepAlive = value;
			return this;
		}

		public Builder sendBufferSize(int value) {
			this.sendBufferSize = value;
			return this;
		}

		public Builder receiveBufferSize(int value) {
			this.receiveBufferSize = value;
			return this;
		}

		public Builder localAddress(InetAddress value) {
			this.localAddress = value;
			return this;
		}

		public Builder sslContext(SSLContext value) {
			this.sslContext = Objects.requireNonNull(value);
			return this;
		}

		public Builder tlsProtocols(List<String> value) {
			this.tlsProtocols = List.copyOf(value);
			if (this.tlsProtocols.isEmpty()) {
				throw new IllegalArgumentException("TLS protocols required");
			}
			return this;
		}

		public Builder proxy(ProxyConfigure value) {
			this.proxy = value;
			return this;
		}

		public Builder leaseOrder(LeaseOrder value) {
			this.leaseOrder = Objects.requireNonNull(value);
			return this;
		}

		public Builder maxStoredBytes(long value) {
			this.maxStoredBytes = value;
			return this;
		}

		public Builder maxInterceptorBodyBytes(long value) {
			this.maxInterceptorBodyBytes = value;
			return this;
		}

		public Builder memoryThreshold(int value) {
			this.memoryThreshold = value;
			return this;
		}

		public Builder temporaryDirectory(Path value) {
			this.temporaryDirectory = value;
			return this;
		}

		public Builder workerThreads(int value) {
			this.workerThreads = value;
			return this;
		}

		public Builder readBufferSize(int value) {
			this.readBufferSize = new JavaOptions(value).readBufferSize();
			return this;
		}

		public ConnectionConfigure build() {
			return new ConnectionConfigure(this);
		}
	}
}
