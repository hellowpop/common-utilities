package com.inzent.apim.utilities.http.client.connector.netty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.SslHandler;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;
import com.inzent.apim.utilities.http.client.spi.ClientConnection;
import com.inzent.apim.utilities.http.client.spi.Http1Protocol;

/**
 * Netty NIO byte transport with demand-driven reads and shared HTTP/1.1 framing.
 */
public final class NettyConnection
		implements ClientConnection {
	private final ConnectionConfigure configure;
	private final Channel channel;
	private final Inbound input = new Inbound();
	private boolean reusable;
	private final boolean forwardProxy;
	private final java.util.concurrent.ExecutorService readers;

	public NettyConnection(ConnectionConfigure configure,
	                       ExecutionContext context,
	                       EventLoopGroup group,
	                       java.util.concurrent.ExecutorService readers) throws Exception {
		this.readers = readers;
		this.configure = configure;
		URI uri = context.url();
		boolean secure = "https".equalsIgnoreCase(uri.getScheme());
		ProxyConfigure proxy = configure.proxy();
		this.forwardProxy = proxy != null && !secure;
		Bootstrap bootstrap = new Bootstrap().group(group)
		                                     .channel(NioSocketChannel.class)
		                                     .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
		                                             (int) Math.min(Integer.MAX_VALUE,
		                                                            configure.connectTimeout()
		                                                                     .toMillis()))
		                                     .option(ChannelOption.TCP_NODELAY,
		                                             configure.tcpNoDelay())
		                                     .option(ChannelOption.SO_KEEPALIVE,
		                                             configure.keepAlive())
		                                     .option(ChannelOption.AUTO_READ,
		                                             false)
		                                     .handler(new ChannelInitializer<SocketChannel>() {
			                                     @Override
			                                     protected void initChannel(SocketChannel channel) throws Exception {
				                                     if (proxy != null && secure) {
					                                     // Pre-encoded Basic header avoids retaining the user's cleartext password.
					                                     io.netty.handler.codec.http.HttpHeaders headers = new io.netty.handler.codec.http.DefaultHttpHeaders();
					                                     if (proxy.authorization() != null) {
						                                     headers.add("Proxy-Authorization",
						                                                 proxy.authorization());
					                                     }
					                                     channel.pipeline()
					                                            .addLast(new HttpProxyHandler(new InetSocketAddress(proxy.host(),
					                                                                                                proxy.port()),
					                                                                          headers));
				                                     }
				                                     if (secure) {
					                                     SSLContext sslContext = configure.sslContext() == null
					                                                             ? SSLContext.getDefault()
					                                                             : configure.sslContext();
					                                     SSLEngine engine = sslContext.createSSLEngine(uri.getHost(),
					                                                                                   Http1Protocol.port(uri));
					                                     engine.setUseClientMode(true);
					                                     SSLParameters parameters = engine.getSSLParameters();
					                                     parameters.setEndpointIdentificationAlgorithm("HTTPS");
					                                     parameters.setProtocols(configure.tlsProtocols()
					                                                                      .toArray(String[]::new));
					                                     engine.setSSLParameters(parameters);
					                                     SslHandler handler = new SslHandler(engine);
					                                     handler.setHandshakeTimeoutMillis(configure.tlsHandshakeTimeout()
					                                                                                .toMillis());
					                                     channel.pipeline()
					                                            .addLast(handler);
				                                     }
				                                     channel.pipeline()
				                                            .addLast(NettyConnection.this.input);
			                                     }
		                                     });
		if (configure.sendBufferSize() > 0) {
			bootstrap.option(ChannelOption.SO_SNDBUF,
			                 configure.sendBufferSize());
		}
		if (configure.receiveBufferSize() > 0) {
			bootstrap.option(ChannelOption.SO_RCVBUF,
			                 configure.receiveBufferSize());
		}
		if (configure.localAddress() != null) {
			bootstrap.localAddress(configure.localAddress(),
			                       0);
		}
		if (proxy != null && secure) {
			bootstrap.resolver(io.netty.resolver.NoopAddressResolverGroup.INSTANCE);
		}
		io.netty.channel.ChannelFuture connected = bootstrap.connect(this.forwardProxy
		                                                             ? proxy.host()
		                                                             : uri.getHost(),
		                                                             this.forwardProxy
		                                                             ? proxy.port()
		                                                             : Http1Protocol.port(uri));
		this.channel = connected.channel();
		context.abortWith(() -> this.channel.close());
		try {
			if (!connected.await(context.remainingMillis()) || !connected.isSuccess()) {
				throw new IOException("Netty connect failed",
				                      connected.cause());
			}
			if (secure) {
				this.channel.read();
				SslHandler ssl = this.channel.pipeline()
				                             .get(SslHandler.class);
				if (!ssl.handshakeFuture()
				        .await(context.remainingMillis()) || !ssl.handshakeFuture()
				                                                 .isSuccess()) {
					throw new IOException("TLS handshake failed",
					                      ssl.handshakeFuture()
					                         .cause());
				}
			}
		}
		catch (Throwable failure) {
			this.channel.close()
			            .awaitUninterruptibly();
			throw failure;
		}
	}

	@Override
	public HttpResponsePayload exchange(ExecutionContext context) throws Exception {
		context.abortWith(() -> this.channel.close());
		if (this.forwardProxy && this.configure.proxy()
		                                       .authorization() != null) {
			context.requestHeaders(context.request()
			                              .headers()
			                              .toBuilder()
			                              .set("Proxy-Authorization",
			                                   this.configure.proxy()
			                                                 .authorization())
			                              .build());
		}
		context.transition(ExecutionContext.State.SENDING,
		                   "SEND",
		                   "START");
		OutputStream output = new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				this.write(new byte[]{(byte) value});
			}

			@Override
			public void write(byte[] bytes,
			                  int offset,
			                  int length) throws IOException {
				byte[] copy = java.util.Arrays.copyOfRange(bytes,
				                                           offset,
				                                           offset + length);
				io.netty.channel.ChannelFuture write = NettyConnection.this.channel.writeAndFlush(Unpooled.wrappedBuffer(copy));
				try {
					if (!write.await(context.remainingMillis()) || !write.isSuccess()) {
						throw new IOException("Netty write failed",
						                      write.cause());
					}
				}
				catch (InterruptedException exception) {
					Thread.currentThread()
					      .interrupt();
					context.cancel();
					throw new IOException("Interrupted",
					                      exception);
				}
				if (context.requestCapture() != null) {
					context.requestCapture()
					       .write(copy);
				}
			}
		};
		HttpResponsePayload response = com.inzent.apim.utilities.http.client.spi.DuplexExchange.run(context,
		                                                                                            this.readers,
		                                                                                            () -> {
			                                                                                            Http1Protocol.write(context,
			                                                                                                                output,
			                                                                                                                this.forwardProxy);
			                                                                                            if (context.shutdownOutput()) {
				                                                                                            ((io.netty.channel.socket.DuplexChannel) this.channel).shutdownOutput()
				                                                                                                                                                  .sync();
			                                                                                            }
		                                                                                            },
		                                                                                            () -> Http1Protocol.read(context,
		                                                                                                                     this.input.stream(context)),
		                                                                                            () -> this.channel.close());
		this.reusable = Http1Protocol.reusable(response,
		                                       context);
		return response;
	}

	@Override
	public boolean reusable() {
		return this.reusable && this.channel.isActive();
	}

	@Override
	public void close() throws IOException {
		this.channel.close()
		            .awaitUninterruptibly();
	}

	private final class Inbound
			extends SimpleChannelInboundHandler<ByteBuf> {
		private final LinkedBlockingQueue<byte[]> data = new LinkedBlockingQueue<>();
		private volatile Throwable failure;
		private byte[] block = new byte[0];
		private int index;

		@Override
		protected void channelRead0(ChannelHandlerContext context,
		                            ByteBuf bytes) {
			if (!bytes.isReadable()) {
				return;
			}
			byte[] copy = new byte[bytes.readableBytes()];
			bytes.readBytes(copy);
			this.data.add(copy);
		}

		@Override
		public void channelInactive(ChannelHandlerContext context) {
			this.data.add(new byte[0]);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext context,
		                            Throwable failure) {
			this.failure = failure;
			context.close();
			this.data.add(new byte[0]);
		}

		private InputStream stream(ExecutionContext execution) {
			return new InputStream() {
				private final byte[] single = new byte[1];
				private boolean ended;

				@Override
				public int read() throws IOException {
					return this.read(this.single,
					                 0,
					                 1) == -1
					       ? -1
					       : this.single[0] & 255;
				}

				@Override
				public int read(byte[] bytes,
				                int offset,
				                int length) throws IOException {
					java.util.Objects.checkFromIndexSize(offset,
					                                     length,
					                                     bytes.length);
					if (length == 0) {
						return 0;
					}
					if (this.ended) {
						return -1;
					}
					if (Inbound.this.index == Inbound.this.block.length) {
						if (Inbound.this.data.isEmpty()) {
							NettyConnection.this.channel.read();
						}
						byte[] next;
						try {
							next = Inbound.this.data.poll(Math.min(execution.remainingMillis(),
							                                       NettyConnection.this.configure.responseIdleTimeout()
							                                                                     .toMillis()),
							                              TimeUnit.MILLISECONDS);
						}
						catch (InterruptedException exception) {
							Thread.currentThread()
							      .interrupt();
							execution.cancel();
							throw new IOException("Interrupted",
							                      exception);
						}
						if (Inbound.this.failure != null) {
							throw new IOException("Netty input failed",
							                      Inbound.this.failure);
						}
						if (next == null) {
							throw new java.net.SocketTimeoutException("Netty read timeout");
						}
						Inbound.this.block = next;
						Inbound.this.index = 0;
						if (Inbound.this.block.length == 0) {
							this.ended = true;
							return -1;
						}
					}
					int count = Math.min(length,
					                     Inbound.this.block.length - Inbound.this.index);
					System.arraycopy(Inbound.this.block,
					                 Inbound.this.index,
					                 bytes,
					                 offset,
					                 count);
					Inbound.this.index += count;
					return count;
				}
			};
		}
	}
}
