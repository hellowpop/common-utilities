package com.inzent.apim.utilities.http.client.connector.netty;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrame;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.HttpHeaders;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;
import com.inzent.apim.utilities.http.client.model.ResponseStatus;
import com.inzent.apim.utilities.http.client.spi.ClientConnection;
import com.inzent.apim.utilities.http.client.spi.Http1Protocol;
import com.inzent.apim.utilities.http.client.store.BodyStore;

/**
 * TLS ALPN HTTP/2 transport, streaming to bounded execution stores.
 */
public final class NettyHttp2Connection
		implements ClientConnection {
	private final Channel parent;
	private final long responseIdleMillis;
	private final CompletableFuture<Void> settingsReady = new CompletableFuture<>();
	private volatile long peerMaxStreams = Integer.MAX_VALUE;
	private volatile boolean accepting = true;

	public NettyHttp2Connection(ConnectionConfigure configure,
	                            ExecutionContext context,
	                            EventLoopGroup group) throws Exception {
		this(configure,
		     context,
		     group,
		     false);
	}

	public NettyHttp2Connection(ConnectionConfigure configure,
	                            ExecutionContext context,
	                            EventLoopGroup group,
	                            boolean reactorNetty) throws Exception {
		this.responseIdleMillis = configure.responseIdleTimeout()
		                                   .toMillis();
		this.parent = NettyTransport.connect(configure,
		                                     context,
		                                     group,
		                                     reactorNetty,
		                                     add -> {
			                                     add.accept(Http2FrameCodecBuilder.forClient()
			                                                                      .initialSettings(new Http2Settings().pushEnabled(false)
			                                                                                                          .maxHeaderListSize(65536))
			                                                                      .encoderEnforceMaxConcurrentStreams(true)
			                                                                      .build());
			                                     add.accept(new SettingsObserver(this));
			                                     add.accept(new Http2MultiplexHandler(new RejectPush()));
		                                     });
		try {
			this.settingsReady.get(context.remainingAcquireMillis(),
			                       TimeUnit.MILLISECONDS);
		}
		catch (Throwable failure) {
			this.parent.close()
			           .awaitUninterruptibly();
			throw failure;
		}
	}

	@Override
	public HttpResponsePayload exchange(ExecutionContext context) throws Exception {
		Receiver receiver = new Receiver(context);
		io.netty.util.concurrent.Future<Http2StreamChannel> opening = new Http2StreamChannelBootstrap(this.parent).handler(new ChannelInitializer<Channel>() {
			                                                                                                          @Override
			                                                                                                          protected void initChannel(Channel stream) {
				                                                                                                          stream.pipeline()
				                                                                                                                .addLast(new io.netty.handler.timeout.ReadTimeoutHandler(NettyHttp2Connection.this.responseIdleMillis,
				                                                                                                                                                                         TimeUnit.MILLISECONDS));
				                                                                                                          stream.pipeline()
				                                                                                                                .addLast(receiver);
			                                                                                                          }
		                                                                                                          })
		                                                                                                          .open();
		Runnable closeOpening = () -> opening.addListener(future -> {
			if (future.isSuccess()) {
				((Http2StreamChannel) future.getNow()).close();
			}
		});
		context.abortWith(closeOpening);
		try {
			if (!opening.await(context.remainingAcquireMillis()) || !opening.isSuccess()) {
				throw new IOException("HTTP/2 stream open failed",
				                      opening.cause());
			}
		}
		catch (Throwable failure) {
			closeOpening.run();
			throw failure;
		}
		Http2StreamChannel stream = opening.getNow();
		context.abortWith(() -> stream.close());
		try {
			Http2Headers headers = new DefaultHttp2Headers().method(context.method()
			                                                               .token())
			                                                .scheme("https")
			                                                .authority(Http1Protocol.authority(context.url()))
			                                                .path(Http1Protocol.target(context.url(),
			                                                                           false));
			for (HttpHeaders.Entry entry : context.request()
			                                      .headers()
			                                      .entries()) {
				if (!entry.name()
				          .equalsIgnoreCase("Host")) {
					headers.add(entry.name()
					                 .toLowerCase(Locale.ROOT),
					            entry.value());
				}
			}
			context.transition(ExecutionContext.State.SENDING,
			                   "SEND",
			                   "START");
			awaitAcquisition(stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers,
			                                                                   context.request()
			                                                                          .body()
			                                                                          .size() == 0)),
			                 context);
			if (context.request()
			           .body()
			           .size() > 0) {
				try (InputStream input = context.request()
				                                .body()
				                                .openStream()) {
					byte[] buffer = new byte[8192];
					for (int count; (count = input.read(buffer)) != -1; ) {
						context.checkCancelled();
						await(stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer(buffer,
						                                                                           0,
						                                                                           count),
						                                                     false)),
						      context);
					}
					await(stream.writeAndFlush(new DefaultHttp2DataFrame(true)),
					      context);
				}
			}
			context.sent(context.request()
			                    .body()
			                    .size());
			return receiver.result.get(context.remainingMillis(),
			                           TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread()
			      .interrupt();
			context.cancel();
			throw exception;
		}
		finally {
			stream.close()
			      .awaitUninterruptibly();
		}
	}

	private static void await(ChannelFuture future,
	                          ExecutionContext context) throws Exception {
		if (!future.await(context.remainingMillis()) || !future.isSuccess()) {
			throw new IOException("HTTP/2 write failed",
			                      future.cause());
		}
	}

	private static void awaitAcquisition(ChannelFuture future,
	                                     ExecutionContext context) throws Exception {
		if (!future.await(context.remainingAcquireMillis())) {
			throw new java.net.SocketTimeoutException("HTTP/2 stream acquire timeout");
		}
		if (!future.isSuccess()) {
			throw new IOException("HTTP/2 headers write failed",
			                      future.cause());
		}
	}

	@Override
	public boolean reusable() {
		return this.parent.isActive() && this.accepting;
	}

	@Override
	public boolean multiplexed() {
		return true;
	}

	@Override
	public int streamCapacity() {
		return (int) Math.min(Integer.MAX_VALUE,
		                      this.peerMaxStreams);
	}

	@Override
	public void close() {
		this.accepting = false;
		this.parent.close()
		           .awaitUninterruptibly();
	}

	private static final class SettingsObserver
			extends io.netty.channel.ChannelInboundHandlerAdapter {
		private final NettyHttp2Connection owner;

		private SettingsObserver(NettyHttp2Connection owner) {
			this.owner = owner;
		}

		@Override
		public void channelRead(ChannelHandlerContext context,
		                        Object message) {
			if (message instanceof io.netty.handler.codec.http2.Http2SettingsFrame settings) {
				Long maximum = settings.settings()
				                       .maxConcurrentStreams();
				if (maximum != null) {
					this.owner.peerMaxStreams = maximum;
				}
				this.owner.settingsReady.complete(null);
			}
			if (message instanceof io.netty.handler.codec.http2.Http2GoAwayFrame) {
				this.owner.accepting = false;
			}
			context.fireChannelRead(message);
		}

		@Override
		public void channelInactive(ChannelHandlerContext context) {
			this.owner.accepting = false;
			this.owner.settingsReady.completeExceptionally(new IOException("HTTP/2 connection closed before SETTINGS"));
			context.fireChannelInactive();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext context,
		                            Throwable failure) {
			this.owner.accepting = false;
			this.owner.settingsReady.completeExceptionally(failure);
			context.close();
		}
	}

	private static final class RejectPush
			extends ChannelInitializer<Channel> {
		@Override
		protected void initChannel(Channel stream) {
			stream.close();
		}
	}

	private static final class Receiver
			extends SimpleChannelInboundHandler<Http2StreamFrame> {
		private final ExecutionContext context;
		private final BodyStore body;
		private final CompletableFuture<HttpResponsePayload> result = new CompletableFuture<>();
		private final List<ResponseStatus> interim = new ArrayList<>();
		private ResponseStatus status;
		private HttpHeaders headers = HttpHeaders.builder()
		                                         .build();
		private HttpHeaders trailers = HttpHeaders.builder()
		                                          .build();

		private Receiver(ExecutionContext context) {
			this.context = context;
			this.body = context.newStore();
		}

		@Override
		protected void channelRead0(ChannelHandlerContext handler,
		                            Http2StreamFrame frame) throws Exception {
			boolean end = false;
			if (frame instanceof Http2HeadersFrame headerFrame) {
				if (headerFrame.headers()
				               .status() != null) {
					ResponseStatus status = new ResponseStatus(Integer.parseInt(headerFrame.headers()
					                                                                       .status()
					                                                                       .toString()),
					                                           "");
					if (status.code() < 200) {
						if (this.interim.size() >= 16) {
							throw new IOException("Too many interim responses");
						}
						this.interim.add(status);
						return;
					}
					if (this.status != null) {
						throw new IOException("Duplicate final HTTP/2 status");
					}
					this.status = status;
					this.headers = convert(headerFrame.headers());
				} else {
					this.trailers = convert(headerFrame.headers());
				}
				end = headerFrame.isEndStream();
			} else if (frame instanceof Http2DataFrame data) {
				if (this.status == null) {
					throw new IOException("HTTP/2 DATA before status");
				}
				byte[] bytes = new byte[data.content()
				                            .readableBytes()];
				data.content()
				    .readBytes(bytes);
				this.body.write(bytes);
				end = data.isEndStream();
			} else if (frame instanceof Http2ResetFrame reset) {
				throw new IOException("HTTP/2 stream reset: " + reset.errorCode());
			}
			if (this.status != null) {
				this.context.response(new HttpResponsePayload(this.status,
				                                              ConnectVersion.HTTP_2,
				                                              this.headers,
				                                              this.trailers,
				                                              this.body,
				                                              this.interim,
				                                              false));
			}
			if (end) {
				if (this.status == null) {
					throw new IOException("HTTP/2 ended without status");
				}
				this.body.seal();
				this.result.complete(new HttpResponsePayload(this.status,
				                                             ConnectVersion.HTTP_2,
				                                             this.headers,
				                                             this.trailers,
				                                             this.body,
				                                             this.interim,
				                                             true));
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext handler,
		                            Throwable failure) {
			this.result.completeExceptionally(failure);
			handler.close();
		}

		@Override
		public void channelInactive(ChannelHandlerContext handler) {
			this.result.completeExceptionally(new IOException("Incomplete HTTP/2 stream"));
		}

		private static HttpHeaders convert(Http2Headers source) {
			HttpHeaders.Builder result = HttpHeaders.builder();
			source.forEach(entry -> {
				if (entry.getKey()
				         .charAt(0) != ':') {
					result.add(entry.getKey()
					                .toString(),
					           entry.getValue()
					                .toString());
				}
			});
			return result.build();
		}
	}
}
