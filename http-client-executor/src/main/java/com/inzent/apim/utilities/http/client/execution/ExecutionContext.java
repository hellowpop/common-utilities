package com.inzent.apim.utilities.http.client.execution;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import com.inzent.apim.utilities.http.client.HttpClientExecutor;
import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
import com.inzent.apim.utilities.http.client.model.CaptureLevel;
import com.inzent.apim.utilities.http.client.model.ConnectMethod;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.HttpHeaders;
import com.inzent.apim.utilities.http.client.model.HttpRequestPayload;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;
import com.inzent.apim.utilities.http.client.model.RequestBody;
import com.inzent.apim.utilities.http.client.model.WireMode;
import com.inzent.apim.utilities.http.client.store.BodyStore;

/**
 * Single-use execution. Close after execute returns; cancel may be called from another thread.
 */
public final class ExecutionContext
		implements AutoCloseable {
	public enum State {
		CREATED,
		PREPARING,
		ACQUIRING,
		CONNECTED,
		SENDING,
		RECEIVING,
		COMPLETED,
		FAILED,
		CANCELLED,
		CLOSED
	}

	public record History(long sequence,
	                      Instant time,
	                      long elapsedNanos,
	                      String stage,
	                      String outcome) {
	}

	private final HttpClientExecutor executor;
	private final URI url;
	private final ConnectMethod method;
	private final ConnectVersion version;
	private RequestBody body;
	private final HttpHeaders headers;
	private ClientHandleInterceptor interceptor;
	private final WireMode requestedMode;
	private final CaptureLevel captureLevel;
	private final boolean shutdownOutput;
	private final int sendChunkBytes;
	private final long sendIntervalNanos;
	private boolean responseReadFinished;
	private final List<BodyStore> stores = new ArrayList<>();
	private final List<History> history = new ArrayList<>();
	private final List<Throwable> interceptorFailures = new ArrayList<>();
	private final long started = System.nanoTime();
	private volatile State state = State.CREATED;
	private volatile Throwable failure;
	private volatile boolean cancelled;
	private volatile Runnable abort;
	private volatile boolean used;
	private volatile boolean running;
	private boolean detached;
	private Thread owner;
	private volatile long deadline;
	private HttpRequestPayload request;
	private HttpResponsePayload response;
	private BodyStore capture;
	private boolean requestSent;
	private boolean connectionReported;
	private long acquireDeadline;
	private long sentBodyBytes;

	private ExecutionContext(Builder builder) {
		this.executor = builder.executor;
		this.url = this.executor.resolve(builder.url,
		                                 builder.path);
		this.method = builder.method == null
		              ? this.executor.method()
		              : builder.method;
		this.version = builder.version == null
		               ? this.executor.version()
		               : builder.version;
		this.body = builder.body == null
		            ? RequestBody.empty()
		            : builder.body;
		this.headers = builder.headers.build();
		this.interceptor = builder.interceptor;
		this.requestedMode = builder.wireMode == null
		                     ? this.executor.wireMode()
		                     : builder.wireMode;
		this.captureLevel = builder.captureLevel;
		this.shutdownOutput = builder.shutdownOutput;
		this.sendChunkBytes = builder.sendChunkBytes;
		this.sendIntervalNanos = builder.sendIntervalNanos;
	}

	public static Builder builder(HttpClientExecutor executor) {
		return new Builder(executor);
	}

	public ExecutionContext execute() {
		this.executor.execute(this);
		return this;
	}

	public URI url() {
		return this.url;
	}

	public ConnectMethod method() {
		return this.method;
	}

	public ConnectVersion version() {
		return this.version;
	}

	public State state() {
		return this.state;
	}

	public Throwable failure() {
		return this.failure;
	}

	public HttpRequestPayload request() {
		return this.request;
	}

	public void requestHeaders(HttpHeaders headers) {
		this.request = new HttpRequestPayload(this.url,
		                                      this.method,
		                                      this.version,
		                                      headers,
		                                      this.request.generatedBody(),
		                                      this.request.body(),
		                                      this.request.wireMode());
	}

	public HttpResponsePayload response() {
		return this.response;
	}

	public BodyStore requestCapture() {
		return this.capture;
	}

	public CaptureLevel captureLevel() {
		return this.captureLevel;
	}

	public int sendChunkBytes() {
		return this.sendChunkBytes;
	}

	public synchronized void signalResponseReadFinished() {
		this.responseReadFinished = true;
		this.notifyAll();
	}

	public synchronized void awaitSendInterval() throws IOException {
		long start = System.nanoTime();
		while (true) {
			this.checkCancelled();
			if (this.responseReadFinished) {
				throw new IOException("Response read finished during upload");
			}
			long now = System.nanoTime();
			long remaining = this.sendIntervalNanos - (now - start);
			if (remaining <= 0) {
				return;
			}
			try {
				TimeUnit.NANOSECONDS.timedWait(this,
				                              Math.min(remaining, this.deadline - now));
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public boolean shutdownOutput() {
		return this.shutdownOutput;
	}

	public boolean requestSent() {
		return this.requestSent;
	}

	public long sentBodyBytes() {
		return this.sentBodyBytes;
	}

	public synchronized List<History> history() {
		return List.copyOf(this.history);
	}

	public synchronized List<Throwable> interceptorFailures() {
		return List.copyOf(this.interceptorFailures);
	}

	public void throwIfFailed() {
		if (this.failure != null) {
			throw new ClientExecutionException("HTTP execution failed",
			                                   this.failure);
		}
	}

	public synchronized void begin() {
		if (this.used || this.state != State.CREATED) {
			throw new IllegalStateException("Context is single-use or closed");
		}
		this.used = true;
		this.running = true;
		this.owner = Thread.currentThread();
		this.deadline = System.nanoTime() + this.executor.configure()
		                                                 .executionTimeout()
		                                                 .toNanos();
		this.transition(State.PREPARING,
		                "PREPARE_BODY",
		                "START");
	}

	public synchronized void transition(State state,
	                                    String stage,
	                                    String outcome) {
		this.state = state;
		if (state == State.ACQUIRING && this.acquireDeadline == 0) {
			this.acquireDeadline = System.nanoTime() + this.executor.configure()
			                                                        .poolAcquireTimeout()
			                                                        .toNanos();
		}
		this.history.add(new History(this.history.size() + 1L,
		                             Instant.now(),
		                             System.nanoTime() - this.started,
		                             stage,
		                             outcome));
	}

	public void prepare() throws IOException {
		BodyStore generated = this.newStore();
		HttpHeaders generatedHeaders = this.body.prepare(generated);
		if (generatedHeaders == null) {
			throw new IOException("RequestBody.prepare must return non-null HttpHeaders");
		}
		generated.seal();
		HttpHeaders.Builder effective = generatedHeaders.toBuilder();
		for (HttpHeaders.Entry entry : this.headers.entries()) {
			effective.remove(entry.name());
		}
		effective.addAll(this.headers);
		BodyStore finalBody = generated;
		if (this.interceptor != null) {
			byte[] content = generated.bytes(this.executor.configure()
			                                              .maxInterceptorBodyBytes());
			byte[] replacement = this.interceptor.contentGenerated(content);
			byte[] result = replacement == null
			                ? content
			                : replacement;
			if (result.length > this.executor.configure()
			                                 .maxInterceptorBodyBytes()) {
				throw new IOException("Interceptor body limit exceeded");
			}
			finalBody = this.newStore();
			finalBody.write(result.clone());
			finalBody.seal();
		}
		WireMode actual = this.requestedMode == WireMode.AUTO
		                  ? (this.interceptor != null && this.version == ConnectVersion.HTTP_1_1
		                     ? WireMode.RAW_HTTP1
		                     : WireMode.STANDARD)
		                  : this.requestedMode;
		this.request = new HttpRequestPayload(this.url,
		                                      this.method,
		                                      this.version,
		                                      effective.build(),
		                                      generated,
		                                      finalBody,
		                                      actual);
		if (this.captureLevel == CaptureLevel.HTTP1_PLAINTEXT_BYTES) {
			this.capture = this.newStore();
		}
		this.checkCancelled();
	}

	public synchronized BodyStore newStore() {
		if (this.state == State.CLOSED) {
			throw new IllegalStateException("Context closed");
		}
		BodyStore store = new BodyStore(this.executor.configure());
		this.stores.add(store);
		return store;
	}

	public synchronized void abortWith(Runnable value) {
		this.abort = value;
		if (value == null) {
			this.detached = true;
		}
		if (this.cancelled && value != null) {
			value.run();
		}
	}

	public synchronized void cancel() {
		if (this.detached || this.state == State.CLOSED || (this.used && !this.running)) {
			return;
		}
		this.cancelled = true;
		this.notifyAll();
		Runnable action = this.abort;
		if (action != null) {
			action.run();
		}
		synchronized (this) {
			if (!this.used && this.state == State.CREATED) {
				this.state = State.CANCELLED;
			}
		}
	}

	public boolean belongsTo(HttpClientExecutor executor) {
		return this.executor == executor;
	}

	public synchronized boolean ownerInterrupted() {
		return this.owner != null && this.owner.isInterrupted();
	}

	public synchronized boolean cancelled() {
		return this.cancelled;
	}

	public void checkCancelled() throws IOException {
		if (Thread.currentThread()
		          .isInterrupted()) {
			this.cancel();
		}
		if (this.cancelled) {
			throw new CancellationException("Execution cancelled or timed out");
		}
		if (System.nanoTime() >= this.deadline) {
			throw new java.net.SocketTimeoutException("Execution deadline exceeded");
		}
	}

	public long remainingMillis() throws IOException {
		this.checkCancelled();
		return Math.max(1,
		                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(this.deadline - System.nanoTime()));
	}

	public void connectResult(boolean success,
	                          boolean reused,
	                          Throwable failure) {
		synchronized (this) {
			if (this.connectionReported) {
				return;
			}
			this.connectionReported = true;
		}
		this.notifyInterceptor(() -> this.interceptor.connectResult(new ClientHandleInterceptor.ConnectResult(success,
		                                                                                                      reused,
		                                                                                                      failure)));
	}

	public synchronized boolean connectionReported() {
		return this.connectionReported;
	}

	public long remainingAcquireMillis() throws IOException {
		this.checkCancelled();
		long remaining = this.acquireDeadline - System.nanoTime();
		if (remaining <= 0) {
			throw new java.net.SocketTimeoutException("HTTP/2 stream acquire timeout");
		}
		return Math.min(this.remainingMillis(),
		                Math.max(1,
		                         java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
	}

	public void sent(long bodyBytes) {
		this.requestSent = true;
		this.sentBodyBytes = bodyBytes;
		this.transition(State.RECEIVING,
		                "SEND",
		                "COMPLETE");
		this.notifyInterceptor(() -> this.interceptor.requestSent(bodyBytes));
	}

	public void response(HttpResponsePayload response) {
		this.response = response;
	}

	public void received(HttpResponsePayload response) {
		this.response = response;
		this.notifyInterceptor(() -> this.interceptor.responseReceived(response));
	}

	private void notifyInterceptor(Runnable notification) {
		if (this.interceptor == null) {
			return;
		}
		try {
			notification.run();
		}
		catch (RuntimeException exception) {
			synchronized (this) {
				this.interceptorFailures.add(exception);
			}
		}
	}

	public synchronized void finish(Throwable failure) {
		this.failure = failure;
		this.abort = null;
		this.running = false;
		this.owner = null;
		this.body = null;
		this.interceptor = null;
		this.transition(failure == null
		                ? State.COMPLETED
		                : (this.cancelled
		                   ? State.CANCELLED
		                   : State.FAILED),
		                "EXECUTION",
		                failure == null
		                ? "COMPLETE"
		                : failure.getClass()
		                         .getSimpleName());
	}

	public synchronized boolean ownedByCurrentThread() {
		return this.running && this.owner == Thread.currentThread();
	}

	@Override
	public synchronized void close() throws IOException {
		if (this.running) {
			throw new IllegalStateException("Cancel first; close after execute returns");
		}
		IOException failure = null;
		for (BodyStore store : this.stores) {
			try {
				store.close();
			}
			catch (IOException exception) {
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
		this.stores.clear();
		this.abort = null;
		this.body = null;
		this.interceptor = null;
		this.state = State.CLOSED;
	}

	public static final class Builder {
		private final HttpClientExecutor executor;
		private URI url;
		private String path;
		private ConnectMethod method;
		private ConnectVersion version;
		private RequestBody body;
		private final HttpHeaders.Builder headers = HttpHeaders.builder();
		private ClientHandleInterceptor interceptor;
		private WireMode wireMode;
		private CaptureLevel captureLevel = CaptureLevel.LOGICAL;
		private boolean shutdownOutput;
		private int sendChunkBytes;
		private long sendIntervalNanos;

		private Builder(HttpClientExecutor executor) {
			this.executor = Objects.requireNonNull(executor);
		}

		public Builder url(URI value) {
			this.url = Objects.requireNonNull(value);
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

		public Builder body(RequestBody value) {
			if (this.body != null) {
				throw new IllegalArgumentException("Body already specified");
			}
			this.body = Objects.requireNonNull(value);
			return this;
		}

		public Builder header(String name,
		                      String value) {
			this.headers.add(name,
			                 value);
			return this;
		}

		public Builder interceptor(ClientHandleInterceptor value) {
			this.interceptor = Objects.requireNonNull(value);
			return this;
		}

		public Builder wireMode(WireMode value) {
			this.wireMode = Objects.requireNonNull(value);
			return this;
		}

		public Builder captureLevel(CaptureLevel value) {
			this.captureLevel = Objects.requireNonNull(value);
			return this;
		}

		public Builder sendChunks(int bytes,
		                          Duration interval) {
			if (this.executor.connectorType() != ConnectorType.JAVA) {
				throw new IllegalArgumentException("sendChunks requires the JAVA connector");
			}
			if (bytes <= 0 || interval.isNegative()) {
				throw new IllegalArgumentException("sendChunks requires positive bytes and a non-negative interval");
			}
			this.sendIntervalNanos = interval.toNanos();
			this.sendChunkBytes = bytes;
			return this;
		}

		public Builder shutdownOutput(boolean value) {
			this.shutdownOutput = value;
			return this;
		}

		public ExecutionContext build() {
			return new ExecutionContext(this);
		}
	}
}
