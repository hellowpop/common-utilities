package com.inzent.apim.utilities.http.client;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectMethod;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.WireMode;
import com.inzent.apim.utilities.http.client.spi.ConnectionPool;
import com.inzent.apim.utilities.http.client.spi.Http1Protocol;

/**
 * Thread-safe reusable executor. Pools and workers belong to this executor.
 */
public final class HttpClientExecutor
		implements AutoCloseable {
	private final ConnectorType type;
	private final HttpClientManager manager;
	private final ConnectionConfigure configure;
	private final URI location;
	private final String path;
	private final ConnectMethod method;
	private final ConnectVersion version;
	private final WireMode wireMode;
	private final Set<ExecutionContext> active = new HashSet<>();
	private final ScheduledThreadPoolExecutor timer;
	private ScheduledFuture<?> interruptWatch;
	private final ConnectionPool pool;
	private boolean closed;

	private HttpClientExecutor(Builder builder) {
		this.manager = builder.manager;
		this.type = builder.type;
		ConnectionConfigure.Builder settings = builder.configure.toBuilder();
		builder.overrides.forEach(override -> override.accept(settings));
		this.configure = settings.build();
		this.location = builder.location;
		this.path = builder.path;
		this.method = builder.method;
		this.version = builder.version;
		this.wireMode = builder.wireMode;
		if (this.type == ConnectorType.JAVA && this.version == ConnectVersion.HTTP_2) {
			throw new IllegalArgumentException("JAVA connector does not support HTTP/2");
		}
		ConnectionConfigure poolSettings = this.configure;
		if (!builder.verifyServerCertificate) {
			if (poolSettings.sslContext() != null) {
				throw new IllegalArgumentException("verifyServerCertificate(false) cannot override a custom SSLContext");
			}
			poolSettings = poolSettings.toBuilder()
			                           .sslContext(createUnverifiedServerContext())
			                           .build();
		}
		this.timer = new ScheduledThreadPoolExecutor(1,
		                                             task -> {
			                                             Thread thread = new Thread(task,
			                                                                        "http-client-deadline");
			                                             thread.setDaemon(true);
			                                             return thread;
		                                             });
		this.timer.setRemoveOnCancelPolicy(true);
		this.pool = new ConnectionPool(this.type,
		                               poolSettings);
	}

	private static SSLContext createUnverifiedServerContext() {
		X509TrustManager trust = new X509TrustManager() {
			@Override
			public void checkServerTrusted(X509Certificate[] chain,
			                               String authType) {
				// Skip chain trust only. The Java connector requests HTTPS endpoint identification.
			}

			@Override
			public void checkClientTrusted(X509Certificate[] chain,
			                               String authType) throws CertificateException {
				throw new CertificateException("Client-only TLS context");
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null,
			             new TrustManager[]{trust},
			             null);
			return context;
		}
		catch (GeneralSecurityException failure) {
			throw new IllegalStateException("Cannot initialize Java TLS context",
			                                failure);
		}
	}

	public ConnectionConfigure configure() {
		return this.configure;
	}

	public ConnectorType connectorType() {
		return this.type;
	}

	public ConnectMethod method() {
		return this.method;
	}

	public ConnectVersion version() {
		return this.version;
	}

	public WireMode wireMode() {
		return this.wireMode;
	}

	public URI resolve(URI url,
	                   String path) {
		if (url != null) {
			if (path != null) {
				throw new IllegalArgumentException("Full URL and path cannot be combined");
			}
			validateUrl(url);
			return URI.create(url.toASCIIString());
		}
		if (this.location == null) {
			throw new IllegalArgumentException("location or URL required");
		}
		String relative = path == null
		                  ? this.path
		                  : path;
		URI part = URI.create(relative);
		if (part.isAbsolute() || part.getRawAuthority() != null || part.getRawFragment() != null) {
			throw new IllegalArgumentException("Path must not have scheme, authority or fragment");
		}
		String base = this.location.getRawPath();
		String tail = part.getRawPath();
		String joined = tail.isEmpty()
		                ? base
		                : base.replaceAll("/$",
		                                  "") + "/" + tail.replaceFirst("^/",
		                                                                "");
		if (joined.isEmpty()) {
			joined = "/";
		}
		URI result = URI.create(this.location.getScheme() + "://" + this.location.getRawAuthority() + joined + (part.getRawQuery() == null
		                                                                                                        ? ""
		                                                                                                        : "?" + part.getRawQuery()));
		validateUrl(result);
		return URI.create(result.toASCIIString());
	}

	private static void validateUrl(URI uri) {
		if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new IllegalArgumentException("Absolute HTTP(S) URL required");
		}
	}

	public void execute(ExecutionContext context) {
		if (!context.belongsTo(this)) {
			throw new IllegalArgumentException("Context belongs to another executor");
		}
		synchronized (this) {
			if (this.closed) {
				throw new IllegalStateException("Executor closed");
			}
			context.begin();
			this.active.add(context);
		}
		ScheduledFuture<?> timeout = null;
		ConnectionPool.Lease lease = null;
		Throwable failure = null;
		try {
			synchronized (this) {
				if (this.interruptWatch == null) {
					this.interruptWatch = this.timer.scheduleWithFixedDelay(this::checkInterrupts,
					                                                        10,
					                                                        10,
					                                                        TimeUnit.MILLISECONDS);
				}
			}
			timeout = this.timer.schedule(context::cancel,
			                              this.configure.executionTimeout()
			                                            .toNanos(),
			                              TimeUnit.NANOSECONDS);
			context.prepare();
			Http1Protocol.validate(context,
			                       this.type);
			context.transition(ExecutionContext.State.ACQUIRING,
			                   "ACQUIRE",
			                   "START");
			lease = this.pool.acquire(context);
			if (!lease.connection()
			          .deferredConnectEvents()) {
				context.connectResult(true,
				                      lease.reused(),
				                      null);
				context.transition(ExecutionContext.State.CONNECTED,
				                   "CONNECT",
				                   lease.reused()
				                   ? "REUSED"
				                   : "NEW");
			}
			context.checkCancelled();
			context.received(lease.connection()
			                      .exchange(context));
			context.checkCancelled();
		}
		catch (Throwable exception) {
			failure = exception;
			if (!context.connectionReported() && context.state() == ExecutionContext.State.ACQUIRING) {
				context.connectResult(false,
				                      false,
				                      exception);
			}
		}
		finally {
			context.abortWith(null);
			if (context.cancelled() && failure == null) {
				failure = new java.util.concurrent.CancellationException("Execution cancelled");
			}
			if (lease != null) {
				try {
					this.pool.release(lease,
					                  failure == null && context.request()
					                                            .wireMode() != WireMode.RAW_HTTP1);
				}
				catch (Throwable cleanup) {
					if (failure == null) {
						failure = cleanup;
					} else {
						failure.addSuppressed(cleanup);
					}
				}
			}
			if (timeout != null) {
				timeout.cancel(false);
			}
			context.finish(failure);
			synchronized (this) {
				this.active.remove(context);
				if (this.active.isEmpty() && this.interruptWatch != null) {
					this.interruptWatch.cancel(false);
					this.interruptWatch = null;
				}
				this.notifyAll();
			}
		}
		if (failure instanceof Error error) {
			throw error;
		}
	}

	private void checkInterrupts() {
		List<ExecutionContext> snapshot;
		synchronized (this) {
			snapshot = List.copyOf(this.active);
		}
		for (ExecutionContext context : snapshot) {
			if (context.ownerInterrupted()) {
				context.cancel();
			}
		}
	}

	@Override
	public void close() {
		this.assertCanClose();
		long end = System.nanoTime() + this.configure.shutdownTimeout()
		                                             .toNanos();
		synchronized (this) {
			if (this.active.stream()
			               .anyMatch(ExecutionContext::ownedByCurrentThread)) {
				throw new IllegalStateException("Cannot close executor from its execution callback");
			}
			if (this.closed) {
				return;
			}
			this.closed = true;
			while (!this.active.isEmpty() && System.nanoTime() < end) {
				try {
					TimeUnit.NANOSECONDS.timedWait(this,
					                               Math.max(1,
					                                        end - System.nanoTime()));
				}
				catch (InterruptedException exception) {
					Thread.currentThread()
					      .interrupt();
					break;
				}
			}
			this.active.forEach(ExecutionContext::cancel);
		}
		try {
			this.pool.close();
		}
		finally {
			this.timer.shutdownNow();
			this.manager.unregister(this);
		}
	}

	public synchronized void assertCanClose() {
		if (this.active.stream()
		               .anyMatch(ExecutionContext::ownedByCurrentThread)) {
			throw new IllegalStateException("Cannot close executor from its execution callback");
		}
	}

	public static final class Builder {
		private final HttpClientManager manager;
		private final ConnectorType type;
		private ConnectionConfigure configure;
		private final java.util.List<Consumer<ConnectionConfigure.Builder>> overrides = new java.util.ArrayList<>();
		private URI location;
		private String path = "";
		private ConnectMethod method = ConnectMethod.GET;
		private ConnectVersion version = ConnectVersion.HTTP_1_1;
		private WireMode wireMode = WireMode.AUTO;
		private boolean verifyServerCertificate = true;

		Builder(HttpClientManager manager,
		        ConnectorType type,
		        ConnectionConfigure configure) {
			this.manager = manager;
			this.type = type;
			this.configure = configure;
		}

		public Builder connectionConfigure(ConnectionConfigure value) {
			this.configure = Objects.requireNonNull(value);
			return this;
		}

		public Builder configure(Consumer<ConnectionConfigure.Builder> value) {
			this.overrides.add(Objects.requireNonNull(value));
			return this;
		}

		public Builder maxConnTotal(int value) {
			return this.configure(builder -> builder.maxConnTotal(value));
		}

		public Builder maxConnPerRoute(int value) {
			return this.configure(builder -> builder.maxConnPerRoute(value));
		}

		public Builder ht5Options(com.inzent.apim.utilities.http.client.config.Ht5Options value) {
			if (this.type != ConnectorType.HT5) {
				throw new IllegalArgumentException("HT5 options require HT5 connector");
			}
			return this.configure(builder -> builder.workerThreads(value.reactorThreads()));
		}

		public Builder nettyOptions(com.inzent.apim.utilities.http.client.config.NettyOptions value) {
			if (this.type != ConnectorType.NETTY && this.type != ConnectorType.REACTOR_NETTY) {
				throw new IllegalArgumentException("Netty options require NETTY or REACTOR_NETTY connector");
			}
			return this.configure(builder -> builder.workerThreads(value.eventLoopThreads()));
		}

		/**
		 * Controls Java TLS server chain validation. HTTPS hostname checks remain enabled.
		 */
		public Builder verifyServerCertificate(boolean value) {
			if (this.type != ConnectorType.JAVA) {
				throw new IllegalArgumentException("Server certificate option requires JAVA connector");
			}
			this.verifyServerCertificate = value;
			return this;
		}

		public Builder javaOptions(com.inzent.apim.utilities.http.client.config.JavaOptions value) {
			if (this.type != ConnectorType.JAVA) {
				throw new IllegalArgumentException("Java options require JAVA connector");
			}
			return this.configure(builder -> builder.readBufferSize(value.readBufferSize()));
		}

		public Builder location(URI value) {
			validateUrl(value);
			if (value.getRawQuery() != null) {
				throw new IllegalArgumentException("Location cannot contain query");
			}
			this.location = value;
			return this;
		}

		public Builder path(String value) {
			this.path = Objects.requireNonNull(value);
			return this;
		}

		public Builder method(ConnectMethod value) {
			this.method = Objects.requireNonNull(value);
			return this;
		}

		public Builder version(ConnectVersion value) {
			this.version = Objects.requireNonNull(value);
			return this;
		}

		public Builder wireMode(WireMode value) {
			this.wireMode = Objects.requireNonNull(value);
			return this;
		}

		public HttpClientExecutor build() {
			HttpClientExecutor executor = new HttpClientExecutor(this);
			this.manager.register(executor);
			return executor;
		}
	}
}
