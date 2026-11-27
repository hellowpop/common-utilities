package com.inzent.apim.utilities.http.client.connector.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;
import reactor.core.Disposable;
import reactor.netty.Connection;
import reactor.netty.NettyPipeline;
import reactor.netty.tcp.TcpClient;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.spi.Http1Protocol;

/**
 * Opens a physical channel for the shared Netty HTTP/1.1 and HTTP/2 handlers.
 */
final class NettyTransport {

	private NettyTransport() {
	}

	static Channel connect(ConnectionConfigure configure,
	                       ExecutionContext context,
	                       EventLoopGroup group,
	                       boolean reactorNetty,
	                       Consumer<Consumer<ChannelHandler>> protocol) throws Exception {
		URI uri = context.url();
		boolean secure = "https".equalsIgnoreCase(uri.getScheme());
		boolean http2 = context.version() == ConnectVersion.HTTP_2;
		ProxyConfigure proxy = configure.proxy();
		boolean tunnel = secure && proxy != null;
		boolean forward = !secure && proxy != null;
		InetSocketAddress remote = forward
		                           ? InetSocketAddress.createUnresolved(proxy.host(),
		                                                                proxy.port())
		                           : InetSocketAddress.createUnresolved(uri.getHost(),
		                                                                Http1Protocol.port(uri));
		SSLContext sslContext = secure && configure.sslContext() == null
		                        ? SSLContext.getDefault()
		                        : configure.sslContext();
		Consumer<Consumer<ChannelHandler>> initialize = add -> {
			if (tunnel) {
				DefaultHttpHeaders headers = new DefaultHttpHeaders();
				if (proxy.authorization() != null) {
					headers.add("Proxy-Authorization",
					            proxy.authorization());
				}
				add.accept(new HttpProxyHandler(new InetSocketAddress(proxy.host(),
				                                                      proxy.port()),
				                                headers));
			}
			if (secure) {
				SSLEngine engine = sslContext.createSSLEngine(uri.getHost(),
				                                              Http1Protocol.port(uri));
				engine.setUseClientMode(true);
				SSLParameters parameters = engine.getSSLParameters();
				parameters.setEndpointIdentificationAlgorithm("HTTPS");
				parameters.setProtocols(configure.tlsProtocols()
				                                 .toArray(String[]::new));
				if (http2) {
					parameters.setApplicationProtocols(new String[]{"h2"});
				}
				engine.setSSLParameters(parameters);
				SslHandler ssl = new SslHandler(engine);
				ssl.setHandshakeTimeoutMillis(configure.tlsHandshakeTimeout()
				                                       .toMillis());
				add.accept(ssl);
			}
			protocol.accept(add);
		};
		Channel channel = reactorNetty
		                  ? connectReactor(configure,
		                                   context,
		                                   group,
		                                   remote,
		                                   tunnel,
		                                   initialize)
		                  : connectBootstrap(configure,
		                                     context,
		                                     group,
		                                     remote,
		                                     tunnel,
		                                     http2,
		                                     initialize);
		try {
			context.abortWith(() -> channel.close());
			context.checkCancelled();
			if (http2 && reactorNetty) {
				// Reactor initializes its bridge in manual-read mode. The H2 parent must read continuously.
				channel.config()
				       .setAutoRead(true);
			}
			if (secure) {
				if (!http2) {
					channel.read();
				}
				SslHandler ssl = channel.pipeline()
				                        .get(SslHandler.class);
				io.netty.util.concurrent.Future<Channel> handshake = ssl.handshakeFuture();
				if (!handshake.await(context.remainingMillis()) || !handshake.isSuccess()) {
					throw new IOException("TLS handshake failed",
					                      handshake.cause());
				}
				if (http2 && !"h2".equals(ssl.engine()
				                             .getApplicationProtocol())) {
					throw new IOException("HTTP/2 ALPN handshake failed");
				}
			}
			return channel;
		}
		catch (Throwable failure) {
			channel.close()
			       .awaitUninterruptibly();
			if (failure instanceof InterruptedException) {
				Thread.currentThread()
				      .interrupt();
				context.cancel();
			}
			throw failure;
		}
	}

	private static Channel connectBootstrap(ConnectionConfigure configure,
	                                        ExecutionContext context,
	                                        EventLoopGroup group,
	                                        InetSocketAddress remote,
	                                        boolean tunnel,
	                                        boolean autoRead,
	                                        Consumer<Consumer<ChannelHandler>> initialize) throws Exception {
		Bootstrap bootstrap = new Bootstrap().group(group)
		                                     .channel(NioSocketChannel.class)
		                                     .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
		                                             connectTimeout(configure))
		                                     .option(ChannelOption.TCP_NODELAY,
		                                             configure.tcpNoDelay())
		                                     .option(ChannelOption.SO_KEEPALIVE,
		                                             configure.keepAlive())
		                                     .option(ChannelOption.AUTO_READ,
		                                             autoRead)
		                                     .handler(new ChannelInitializer<Channel>() {
			                                     @Override
			                                     protected void initChannel(Channel channel) {
				                                     initialize.accept(handler -> channel.pipeline()
				                                                                         .addLast(handler));
			                                     }
		                                     });
		if (configure.localAddress() != null) {
			bootstrap.localAddress(configure.localAddress(),
			                       0);
		}
		if (tunnel) {
			bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
		}
		if (configure.sendBufferSize() > 0) {
			bootstrap.option(ChannelOption.SO_SNDBUF,
			                 configure.sendBufferSize());
		}
		if (configure.receiveBufferSize() > 0) {
			bootstrap.option(ChannelOption.SO_RCVBUF,
			                 configure.receiveBufferSize());
		}
		ChannelFuture connected = bootstrap.connect(remote);
		try {
			context.abortWith(() -> connected.channel()
			                                 .close());
			if (!connected.await(context.remainingMillis()) || !connected.isSuccess()) {
				throw new IOException("Netty connect failed",
				                      connected.cause());
			}
			return connected.channel();
		}
		catch (Throwable failure) {
			connected.channel()
			         .close()
			         .awaitUninterruptibly();
			throw failure;
		}
	}

	private static Channel connectReactor(ConnectionConfigure configure,
	                                      ExecutionContext context,
	                                      EventLoopGroup group,
	                                      InetSocketAddress remote,
	                                      boolean tunnel,
	                                      Consumer<Consumer<ChannelHandler>> initialize) throws Exception {
		TcpClient client = TcpClient.newConnection()
		                            .runOn(group)
		                            .remoteAddress(() -> remote)
		                            .resolver(tunnel
		                                      ? NoopAddressResolverGroup.INSTANCE
		                                      : DefaultAddressResolverGroup.INSTANCE)
		                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
		                                    connectTimeout(configure))
		                            .option(ChannelOption.TCP_NODELAY,
		                                    configure.tcpNoDelay())
		                            .option(ChannelOption.SO_KEEPALIVE,
		                                    configure.keepAlive())
		                            .doOnChannelInit((observer, channel, address) -> {
			                            initialize.accept(handler -> channel.pipeline()
			                                                                .addBefore(NettyPipeline.ReactiveBridge,
			                                                                           null,
			                                                                           handler));
			                            // The protocol handlers own inbound reads. Release any unhandled parent frames.
			                            channel.pipeline()
			                                   .addBefore(NettyPipeline.ReactiveBridge,
			                                              null,
			                                              new ChannelInboundHandlerAdapter() {
				                                              @Override
				                                              public void channelRead(ChannelHandlerContext handler,
				                                                                      Object message) {
					                                              ReferenceCountUtil.release(message);
				                                              }
			                                              });
		                            });
		if (configure.localAddress() != null) {
			client = client.bindAddress(() -> new InetSocketAddress(configure.localAddress(),
			                                                        0));
		}
		if (configure.sendBufferSize() > 0) {
			client = client.option(ChannelOption.SO_SNDBUF,
			                       configure.sendBufferSize());
		}
		if (configure.receiveBufferSize() > 0) {
			client = client.option(ChannelOption.SO_RCVBUF,
			                       configure.receiveBufferSize());
		}
		CompletableFuture<Connection> connected = new CompletableFuture<>();
		Disposable connecting = client.connect()
		                              .doOnDiscard(Connection.class,
		                                           Connection::dispose)
		                              .subscribe(connection -> {
			                                         if (!connected.complete(connection)) {
				                                         connection.dispose();
			                                         }
		                                         },
		                                         connected::completeExceptionally);
		Runnable abort = () -> {
			if (connected.cancel(false)) {
				connecting.dispose();
			} else {
				connected.thenAccept(Connection::dispose);
			}
		};
		try {
			context.abortWith(abort);
			return connected.get(context.remainingMillis(),
			                     TimeUnit.MILLISECONDS)
			                .channel();
		}
		catch (InterruptedException failure) {
			Thread.currentThread()
			      .interrupt();
			context.cancel();
			throw failure;
		}
		catch (Throwable failure) {
			abort.run();
			throw failure;
		}
	}

	private static int connectTimeout(ConnectionConfigure configure) {
		return (int) Math.min(Integer.MAX_VALUE,
		                      configure.connectTimeout()
		                               .toMillis());
	}
}
