package com.inzent.apim.utilities.http.client.connector.ht5;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.EndpointInfo;
import org.apache.hc.client5.http.auth.AuthExchange;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.util.Timeout;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.HttpHeaders;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;
import com.inzent.apim.utilities.http.client.model.ResponseStatus;
import com.inzent.apim.utilities.http.client.spi.ClientConnection;
import com.inzent.apim.utilities.http.client.store.BodyStore;

/**
 * Apache full async route establishment, with a shared native HTTP/2 connection.
 */
public final class Ht5Http2Connection
		implements ClientConnection {
	private static final String PROGRESS = Ht5Http2Connection.class.getName() + ".progress";
	private final CloseableHttpAsyncClient client;
	private final ConnectionConfigure configure;
	private final HttpHost proxyHost;
	private final Set<String> reportedSessions = new HashSet<>();
	private volatile boolean closed;
	private Ht5ConnectionRegistry connections;

	public Ht5Http2Connection(ConnectionConfigure configure,
	                          ExecutionContext context) throws Exception {
		this.configure = configure;
		ProxyConfigure proxy = configure.proxy();
		this.proxyHost = proxy == null
		                 ? null
		                 : new HttpHost("http",
		                                proxy.host(),
		                                proxy.port());
		PoolingAsyncClientConnectionManager manager = new PoolingAsyncClientConnectionManagerBuilder() {
			@Override
			protected org.apache.hc.client5.http.nio.AsyncClientConnectionOperator createConnectionOperator(org.apache.hc.core5.http.nio.ssl.TlsStrategy tls,
			                                                                                                org.apache.hc.client5.http.SchemePortResolver scheme,
			                                                                                                org.apache.hc.client5.http.DnsResolver dns) {
				Ht5Http2Connection.this.connections = new Ht5ConnectionRegistry(tls,
				                                                                scheme,
				                                                                dns);
				return Ht5Http2Connection.this.connections;
			}
		}.setMaxConnTotal(1)
		 .setMaxConnPerRoute(1)
		 .setMessageMultiplexing(true)
		 .setTlsStrategy(ClientTlsStrategyBuilder.create()
		                                         .setSslContext(configure.sslContext() == null
		                                                        ? SSLContext.getDefault()
		                                                        : configure.sslContext())
		                                         .setTlsVersions(configure.tlsProtocols()
		                                                                  .toArray(String[]::new))
		                                         .buildAsync())
		 .setDefaultTlsConfig(TlsConfig.custom()
		                               .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
		                               .setHandshakeTimeout(Timeout.ofMilliseconds(configure.tlsHandshakeTimeout()
		                                                                                    .toMillis()))
		                               .build())
		 .setDefaultConnectionConfig(ConnectionConfig.custom()
		                                             .setConnectTimeout(Timeout.ofMilliseconds(configure.connectTimeout()
		                                                                                                .toMillis()))
		                                             .build())
		 .build();
		IOReactorConfig reactor = IOReactorConfig.custom()
		                                         .setIoThreadCount(configure.workerThreads())
		                                         .setTcpNoDelay(configure.tcpNoDelay())
		                                         .setSoKeepAlive(configure.keepAlive())
		                                         .setSndBufSize(configure.sendBufferSize())
		                                         .setRcvBufSize(configure.receiveBufferSize())
		                                         .build();
		this.client = HttpAsyncClients.custom()
		                              .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
		                              .setConnectionManager(manager)
		                              .setIOReactorConfig(reactor)
		                              .setIoSessionDecorator(Ht5CommandSession::new)
		                              .setRoutePlanner((target, nativeContext) -> {
			                              HttpHost normalized = target.getPort() >= 0
			                                                    ? target
			                                                    : new HttpHost(target.getSchemeName(),
			                                                                   target.getHostName(),
			                                                                   443);
			                              return this.proxyHost == null
			                                     ? new HttpRoute(normalized,
			                                                     configure.localAddress(),
			                                                     true)
			                                     : new HttpRoute(normalized,
			                                                     configure.localAddress(),
			                                                     this.proxyHost,
			                                                     true);
		                              })
		                              .setDefaultRequestConfig(RequestConfig.custom()
		                                                                    .setHardCancellationEnabled(false)
		                                                                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(configure.poolAcquireTimeout()
		                                                                                                                                 .toMillis()))
		                                                                    .build())
		                              .disableAutomaticRetries()
		                              .disableRedirectHandling()
		                              .disableCookieManagement()
		                              .disableConnectionState()
		                              .disableContentCompression()
		                              .disableAuthCaching()
		                              .addRequestInterceptorLast((request, details, nativeContext) -> {
			                              Progress progress = (Progress) nativeContext.getAttribute(PROGRESS);
			                              if (progress != null) {
				                              progress.lastActivity = System.nanoTime();
				                              progress.started.set(true);
			                              }
		                              })
		                              .addExecInterceptorBefore(ChainElement.MAIN_TRANSPORT.name(),
		                                                        "execution-connect-observer",
		                                                        (request, entity, scope, chain, callback) -> {
			                                                        // Apache 5.6's sharing facade only tracks entries leased after HTTP/2 is established.
			                                                        // Re-lease the connected route before sending any application bytes, including the first exchange.
			                                                        scope.execRuntime.markConnectionReusable(null,
			                                                                                                 org.apache.hc.core5.util.TimeValue.NEG_ONE_MILLISECOND);
			                                                        scope.execRuntime.releaseEndpoint();
			                                                        org.apache.hc.core5.concurrent.ComplexCancellable handoff = new org.apache.hc.core5.concurrent.ComplexCancellable();
			                                                        scope.cancellableDependency.setDependency(handoff);
			                                                        org.apache.hc.core5.concurrent.Cancellable acquisition = scope.execRuntime.acquireEndpoint(scope.exchangeId,
			                                                                                                                                                   scope.route,
			                                                                                                                                                   null,
			                                                                                                                                                   scope.clientContext,
			                                                                                                                                                   new FutureCallback<org.apache.hc.client5.http.async.AsyncExecRuntime>() {
				                                                                                                                                                   @Override
				                                                                                                                                                   public void completed(org.apache.hc.client5.http.async.AsyncExecRuntime runtime) {
					                                                                                                                                                   Progress progress = (Progress) scope.clientContext.getAttribute(PROGRESS);
					                                                                                                                                                   if (progress != null) {
						                                                                                                                                                   progress.ready.complete(runtime.getEndpointInfo());
					                                                                                                                                                   }
					                                                                                                                                                   Ht5StreamRuntime streamRuntime = new Ht5StreamRuntime(runtime,
					                                                                                                                                                                                                         Ht5Http2Connection.this.connections,
					                                                                                                                                                                                                         progress.cancellation);
					                                                                                                                                                   org.apache.hc.client5.http.async.AsyncExecChain.Scope streamScope = new org.apache.hc.client5.http.async.AsyncExecChain.Scope(scope.exchangeId,
					                                                                                                                                                                                                                                                                                 scope.route,
					                                                                                                                                                                                                                                                                                 scope.originalRequest,
					                                                                                                                                                                                                                                                                                 scope.cancellableDependency,
					                                                                                                                                                                                                                                                                                 scope.clientContext,
					                                                                                                                                                                                                                                                                                 streamRuntime,
					                                                                                                                                                                                                                                                                                 scope.scheduler,
					                                                                                                                                                                                                                                                                                 scope.execCount);
					                                                                                                                                                   org.apache.hc.client5.http.async.AsyncExecCallback streamCallback = new org.apache.hc.client5.http.async.AsyncExecCallback() {
						                                                                                                                                                   @Override
						                                                                                                                                                   public org.apache.hc.core5.http.nio.AsyncDataConsumer handleResponse(HttpResponse response,
						                                                                                                                                                                                                                        EntityDetails details) throws org.apache.hc.core5.http.HttpException, IOException {
							                                                                                                                                                   return callback.handleResponse(response,
							                                                                                                                                                                                  details);
						                                                                                                                                                   }

						                                                                                                                                                   @Override
						                                                                                                                                                   public void handleInformationResponse(HttpResponse response) throws org.apache.hc.core5.http.HttpException, IOException {
							                                                                                                                                                   callback.handleInformationResponse(response);
						                                                                                                                                                   }

						                                                                                                                                                   @Override
						                                                                                                                                                   public void completed() {
							                                                                                                                                                   callback.completed();
						                                                                                                                                                   }

						                                                                                                                                                   @Override
						                                                                                                                                                   public void failed(Exception failure) {
							                                                                                                                                                   streamRuntime.releaseAfterStreamFailure();
							                                                                                                                                                   callback.failed(failure);
						                                                                                                                                                   }
					                                                                                                                                                   };
					                                                                                                                                                   try {
						                                                                                                                                                   chain.proceed(request,
						                                                                                                                                                                 entity,
						                                                                                                                                                                 streamScope,
						                                                                                                                                                                 streamCallback);
					                                                                                                                                                   }
					                                                                                                                                                   catch (Exception failure) {
						                                                                                                                                                   streamCallback.failed(failure);
					                                                                                                                                                   }
				                                                                                                                                                   }

				                                                                                                                                                   @Override
				                                                                                                                                                   public void failed(Exception failure) {
					                                                                                                                                                   callback.failed(failure);
				                                                                                                                                                   }

				                                                                                                                                                   @Override
				                                                                                                                                                   public void cancelled() {
					                                                                                                                                                   callback.failed(new java.io.InterruptedIOException("HTTP/2 route acquisition cancelled"));
				                                                                                                                                                   }
			                                                                                                                                                   });
			                                                        handoff.setDependency(acquisition);
		                                                        })
		                              .build();
		this.client.start();
	}

	@Override
	public HttpResponsePayload exchange(ExecutionContext context) throws Exception {
		BasicHttpRequest request = new BasicHttpRequest(context.method()
		                                                       .token(),
		                                                context.url());
		request.setVersion(HttpVersion.HTTP_2);
		for (HttpHeaders.Entry header : context.request()
		                                       .headers()
		                                       .entries()) {
			// H2RequestContent writes this validated value from the entity details.
			if (!header.name()
			           .equalsIgnoreCase("Content-Length")) {
				request.addHeader(header.name(),
				                  header.value());
			}
		}
		Progress progress = new Progress();
		AtomicBoolean sent = new AtomicBoolean();
		Consumer consumer = new Consumer(context,
		                                 progress);
		Entity entity = new Entity(context,
		                           sent);
		HttpClientContext nativeContext = HttpClientContext.create();
		nativeContext.setAttribute(PROGRESS,
		                           progress);
		nativeContext.setRequestConfig(RequestConfig.custom()
		                                            .setHardCancellationEnabled(false)
		                                            .setConnectionRequestTimeout(Timeout.ofMilliseconds(context.remainingAcquireMillis()))
		                                            .build());
		this.proxyAuthentication(nativeContext);
		BasicRequestProducer producer = new BasicRequestProducer(request,
		                                                         entity);
		Future<HttpResponsePayload> future;
		try {
			future = this.client.execute(producer,
			                             consumer,
			                             nativeContext,
			                             null);
		}
		catch (Throwable failure) {
			entity.releaseResources();
			throw failure;
		}
		context.abortWith(() -> {
			progress.cancellation.cancel();
			future.cancel(true);
		});
		try {
			while (true) {
				context.checkCancelled();
				this.reportConnection(context,
				                      progress);
				if (!progress.started.get()) {
					context.remainingAcquireMillis();
				} else if (System.nanoTime() - progress.lastActivity > this.configure.responseIdleTimeout()
				                                                                     .toNanos()) {
					throw new java.net.SocketTimeoutException("HTTP/2 response idle timeout");
				}
				if ((sent.get() || (progress.started.get() && context.request()
				                                                     .body()
				                                                     .size() == 0)) && !context.requestSent()) {
					context.sent(context.request()
					                    .body()
					                    .size());
				}
				try {
					HttpResponsePayload response = future.get(Math.min(10,
					                                                   context.remainingMillis()),
					                                          TimeUnit.MILLISECONDS);
					this.reportConnection(context,
					                      progress);
					if (sent.get() && !context.requestSent()) {
						context.sent(context.request()
						                    .body()
						                    .size());
					}
					return response;
				}
				catch (java.util.concurrent.TimeoutException pending) { /* stream-local notifications stay on the caller thread */ }
			}
		}
		catch (InterruptedException failure) {
			Thread.currentThread()
			      .interrupt();
			context.cancel();
			throw failure;
		}
		finally {
			if (!future.isDone()) {
				progress.cancellation.cancel();
				future.cancel(true);
			}
			entity.releaseResources();
		}
	}

	private void reportConnection(ExecutionContext context,
	                              Progress progress) throws Exception {
		if (!context.connectionReported() && progress.ready.isDone()) {
			EndpointInfo endpoint = progress.ready.get();
			if (endpoint == null || !HttpVersion.HTTP_2.equals(endpoint.getProtocol())) {
				throw new IOException("Server did not negotiate HTTP/2");
			}
			String session = Base64.getEncoder()
			                       .encodeToString(endpoint.getSslSession()
			                                               .getId());
			boolean reused;
			synchronized (this.reportedSessions) {
				reused = !this.reportedSessions.add(session);
			}
			context.connectResult(true,
			                      reused,
			                      null);
			context.transition(ExecutionContext.State.CONNECTED,
			                   "CONNECT",
			                   reused
			                   ? "REUSED"
			                   : "NEW");
			context.transition(ExecutionContext.State.SENDING,
			                   "STREAM_ACQUIRE",
			                   "READY");
		}
	}

	private void proxyAuthentication(HttpClientContext context) {
		if (this.proxyHost == null || this.configure.proxy()
		                                            .authorization() == null) {
			return;
		}
		String decoded = new String(Base64.getDecoder()
		                                  .decode(this.configure.proxy()
		                                                        .authorization()
		                                                        .substring(6)),
		                            StandardCharsets.ISO_8859_1);
		int colon = decoded.indexOf(':');
		BasicScheme scheme = new BasicScheme(StandardCharsets.ISO_8859_1);
		scheme.initPreemptive(new UsernamePasswordCredentials(decoded.substring(0,
		                                                                        colon),
		                                                      decoded.substring(colon + 1)
		                                                             .toCharArray()));
		context.resetAuthExchange(this.proxyHost,
		                          scheme);
		context.getAuthExchange(this.proxyHost)
		       .setState(AuthExchange.State.SUCCESS);
	}

	@Override
	public boolean reusable() {
		IOReactorStatus status = this.client.getStatus();
		return !this.closed && status != IOReactorStatus.SHUT_DOWN && status != IOReactorStatus.SHUTTING_DOWN;
	}

	@Override
	public boolean multiplexed() {
		return true;
	}

	@Override
	public int streamCapacity() {
		return Integer.MAX_VALUE;
	}

	@Override
	public boolean deferredConnectEvents() {
		return true;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.client.close(CloseMode.IMMEDIATE);
			this.connections.clear();
		}
	}

	private static final class Progress {
		private final CompletableFuture<EndpointInfo> ready = new CompletableFuture<>();
		private final AtomicBoolean started = new AtomicBoolean();
		private final org.apache.hc.core5.concurrent.ComplexCancellable cancellation = new org.apache.hc.core5.concurrent.ComplexCancellable();
		private volatile long lastActivity = System.nanoTime();
	}

	private static final class Entity
			implements AsyncEntityProducer {
		private final ExecutionContext context;
		private final AtomicBoolean sent;
		private volatile InputStream input;
		private ByteBuffer pending = ByteBuffer.allocate(0);
		private boolean ended;

		private Entity(ExecutionContext context,
		               AtomicBoolean sent) throws IOException {
			this.context = context;
			this.sent = sent;
			this.input = context.request()
			                    .body()
			                    .openStream();
		}

		@Override
		public int available() {
			return this.ended
			       ? 0
			       : 8192;
		}

		@Override
		public void produce(DataStreamChannel channel) throws IOException {
			if (this.ended) {
				return;
			}
			if (!this.pending.hasRemaining()) {
				byte[] bytes = new byte[8192];
				InputStream source = this.input;
				if (source == null) {
					throw new org.apache.hc.core5.http.HttpStreamResetException("HTTP/2 request body is closed");
				}
				int count;
				try {
					count = source.read(bytes);
				}
				catch (IOException failure) {
					throw new org.apache.hc.core5.http.HttpStreamResetException("HTTP/2 request body read failed",
					                                                            failure);
				}
				if (count == -1) {
					channel.endStream();
					this.sent.set(true);
					this.ended = true;
					return;
				}
				this.pending = ByteBuffer.wrap(bytes,
				                               0,
				                               count);
			}
			channel.write(this.pending);
		}

		@Override
		public long getContentLength() {
			return this.context.request()
			                   .body()
			                   .size();
		}

		@Override
		public String getContentType() {
			return this.context.request()
			                   .headers()
			                   .first("Content-Type");
		}

		@Override
		public String getContentEncoding() {
			return this.context.request()
			                   .headers()
			                   .first("Content-Encoding");
		}

		@Override
		public boolean isChunked() {
			return false;
		}

		@Override
		public Set<String> getTrailerNames() {
			return Set.of();
		}

		@Override
		public boolean isRepeatable() {
			return false;
		}

		@Override
		public void failed(Exception failure) {
			this.releaseResources();
		}

		@Override
		public void releaseResources() {
			InputStream source = this.input;
			this.input = null;
			try {
				if (source != null) {
					source.close();
				}
			}
			catch (IOException ignored) {
			}
		}
	}

	private static final class Consumer
			implements AsyncResponseConsumer<HttpResponsePayload> {
		private final Progress progress;
		private final ExecutionContext context;
		private final BodyStore body;
		private final List<ResponseStatus> interim = new ArrayList<>();
		private ResponseStatus status;
		private HttpHeaders headers;
		private FutureCallback<HttpResponsePayload> callback;

		private Consumer(ExecutionContext context,
		                 Progress progress) {
			this.progress = progress;
			this.context = context;
			this.body = context.newStore();
		}

		@Override
		public void consumeResponse(HttpResponse response,
		                            EntityDetails details,
		                            HttpContext httpContext,
		                            FutureCallback<HttpResponsePayload> callback) throws IOException {
			this.progress.lastActivity = System.nanoTime();
			if (!HttpVersion.HTTP_2.equals(response.getVersion())) {
				throw new IOException("Server did not negotiate HTTP/2");
			}
			this.status = new ResponseStatus(response.getCode(),
			                                 "");
			this.headers = convert(List.of(response.getHeaders()));
			this.callback = callback;
			this.context.response(new HttpResponsePayload(this.status,
			                                              ConnectVersion.HTTP_2,
			                                              this.headers,
			                                              HttpHeaders.builder()
			                                                         .build(),
			                                              this.body,
			                                              this.interim,
			                                              false));
			if (details == null) {
				this.streamEnd(List.of());
			}
		}

		@Override
		public void informationResponse(HttpResponse response,
		                                HttpContext context) throws IOException {
			this.progress.lastActivity = System.nanoTime();
			if (this.interim.size() >= 16) {
				throw new org.apache.hc.core5.http.HttpStreamResetException("Too many interim responses");
			}
			this.interim.add(new ResponseStatus(response.getCode(),
			                                    ""));
		}

		@Override
		public void updateCapacity(CapacityChannel channel) throws IOException {
			channel.update(8192);
		}

		@Override
		public void consume(ByteBuffer bytes) throws IOException {
			this.progress.lastActivity = System.nanoTime();
			byte[] block = new byte[bytes.remaining()];
			bytes.get(block);
			try {
				this.body.write(block);
			}
			catch (IOException failure) {
				throw new org.apache.hc.core5.http.HttpStreamResetException("HTTP/2 response storage failed",
				                                                            failure);
			}
		}

		@Override
		public void streamEnd(List<? extends Header> trailers) throws IOException {
			try {
				this.body.seal();
			}
			catch (IOException failure) {
				throw new org.apache.hc.core5.http.HttpStreamResetException("HTTP/2 response storage close failed",
				                                                            failure);
			}
			this.callback.completed(new HttpResponsePayload(this.status,
			                                                ConnectVersion.HTTP_2,
			                                                this.headers,
			                                                convert(trailers),
			                                                this.body,
			                                                this.interim,
			                                                true));
		}

		@Override
		public void failed(Exception failure) {
			if (this.callback != null) {
				this.callback.failed(failure);
			}
		}

		@Override
		public void releaseResources() {
		}

		private static HttpHeaders convert(List<? extends Header> headers) {
			HttpHeaders.Builder result = HttpHeaders.builder();
			if (headers != null) {
				headers.forEach(header -> result.add(header.getName(),
				                                     header.getValue()));
			}
			return result.build();
		}
	}
}
