package com.inzent.apim.utilities.http.client;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

final class TlsFixture
		implements AutoCloseable {
	final SSLContext client;
	private final EventLoopGroup boss = new NioEventLoopGroup(1);
	private final EventLoopGroup worker = new NioEventLoopGroup(1);
	private final Channel listener;
	final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
	final java.util.List<Channel> channels = new java.util.concurrent.CopyOnWriteArrayList<>();
	final java.util.concurrent.LinkedBlockingQueue<Boolean> settingsAcks = new java.util.concurrent.LinkedBlockingQueue<>();
	private final java.util.function.BiConsumer<ChannelHandlerContext, FullHttpRequest> handler;

	TlsFixture(Path directory,
	           boolean http2,
	           boolean mutual) throws Exception {
		this(directory,
		     http2,
		     mutual,
		     100,
		     null);
	}

	TlsFixture(Path directory,
	           boolean http2,
	           boolean mutual,
	           int maxStreams,
	           java.util.function.BiConsumer<ChannelHandlerContext, FullHttpRequest> handler) throws Exception {
		this.handler = handler;
		Files.createDirectories(directory);
		Path store = directory.resolve("fixture.p12");
		if (!Files.exists(store)) {
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"),
			                                             "bin",
			                                             "keytool.exe")
			                                         .toString(),
			                                     "-genkeypair",
			                                     "-alias",
			                                     "fixture",
			                                     "-keyalg",
			                                     "RSA",
			                                     "-keysize",
			                                     "2048",
			                                     "-validity",
			                                     "3",
			                                     "-dname",
			                                     "CN=localhost",
			                                     "-ext",
			                                     "SAN=dns:localhost,ip:127.0.0.1",
			                                     "-storetype",
			                                     "PKCS12",
			                                     "-keystore",
			                                     store.toString(),
			                                     "-storepass",
			                                     "fixturepass",
			                                     "-noprompt").redirectErrorStream(true)
			                                                 .redirectOutput(directory.resolve("keytool.log")
			                                                                          .toFile())
			                                                 .start();
			if (!process.waitFor(20,
			                     TimeUnit.SECONDS) || process.exitValue() != 0) {
				process.destroyForcibly();
				throw new IllegalStateException("keytool failed");
			}
		}
		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (InputStream input = Files.newInputStream(store)) {
			keys.load(input,
			          "fixturepass".toCharArray());
		}
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys,
		                 "fixturepass".toCharArray());
		TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagers.init(keys);
		this.client = SSLContext.getInstance("TLS");
		this.client.init(keyManagers.getKeyManagers(),
		                 trustManagers.getTrustManagers(),
		                 null);
		SslContextBuilder tls = SslContextBuilder.forServer(keyManagers)
		                                         .trustManager(trustManagers)
		                                         .clientAuth(mutual
		                                                     ? ClientAuth.REQUIRE
		                                                     : ClientAuth.NONE);
		if (http2) {
			tls.applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
			                                                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
			                                                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
			                                                            ApplicationProtocolNames.HTTP_2));
		}
		SslContext ssl = tls.build();
		this.listener = new ServerBootstrap().group(this.boss,
		                                            this.worker)
		                                     .channel(NioServerSocketChannel.class)
		                                     .childHandler(new ChannelInitializer<SocketChannel>() {
			                                     @Override
			                                     protected void initChannel(SocketChannel channel) {
				                                     TlsFixture.this.connections.incrementAndGet();
				                                     TlsFixture.this.channels.add(channel);
				                                     channel.pipeline()
				                                            .addLast(ssl.newHandler(channel.alloc()));
				                                     if (http2) {
					                                     channel.pipeline()
					                                            .addLast(new ApplicationProtocolNegotiationHandler("") {
						                                            @Override
						                                            protected void configurePipeline(ChannelHandlerContext context,
						                                                                             String protocol) {
							                                            if (!ApplicationProtocolNames.HTTP_2.equals(protocol)) {
								                                            context.close();
								                                            return;
							                                            }
							                                            context.pipeline()
							                                                   .addLast(Http2FrameCodecBuilder.forServer()
							                                                                                  .initialSettings(new io.netty.handler.codec.http2.Http2Settings().maxConcurrentStreams(maxStreams))
							                                                                                  .build(),
							                                                            new io.netty.channel.ChannelInboundHandlerAdapter() {
								                                                            @Override
								                                                            public void channelRead(ChannelHandlerContext handler,
								                                                                                    Object message) {
									                                                            if (message instanceof io.netty.handler.codec.http2.Http2SettingsAckFrame) {
										                                                            TlsFixture.this.settingsAcks.add(Boolean.TRUE);
									                                                            }
									                                                            handler.fireChannelRead(message);
								                                                            }
							                                                            },
							                                                            new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
								                                                            @Override
								                                                            protected void initChannel(Channel stream) {
									                                                            stream.pipeline()
									                                                                  .addLast(new Http2StreamFrameToHttpObjectCodec(true));
									                                                            TlsFixture.this.install(stream);
								                                                            }
							                                                            }));
						                                            }
					                                            });
				                                     } else {
					                                     channel.pipeline()
					                                            .addLast(new HttpServerCodec());
					                                     TlsFixture.this.install(channel);
				                                     }
			                                     }
		                                     })
		                                     .bind(0)
		                                     .sync()
		                                     .channel();
	}

	private void install(Channel channel) {
		channel.pipeline()
		       .addLast(new HttpObjectAggregator(1024 * 1024));
		channel.pipeline()
		       .addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
			       @Override
			       protected void channelRead0(ChannelHandlerContext context,
			                                   FullHttpRequest request) {
				       if (TlsFixture.this.handler != null) {
					       TlsFixture.this.handler.accept(context,
					                                      request);
					       return;
				       }
				       if (request.uri()
				                  .equals("/stall")) {
					       return;
				       }
				       byte[] bytes = new byte[request.content()
				                                      .readableBytes()];
				       request.content()
				              .readBytes(bytes);
				       DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                      HttpResponseStatus.OK,
				                                                                      Unpooled.wrappedBuffer(bytes.length == 0
				                                                                                             ? new byte[]{111,
				                                                                                                          107}
				                                                                                             : bytes));
				       response.headers()
				               .set("content-length",
				                    response.content()
				                            .readableBytes());
				       response.headers()
				               .set("x-fixture",
				                    "tls");
				       context.writeAndFlush(response);
			       }
		       });
	}

	int port() {
		return ((java.net.InetSocketAddress) this.listener.localAddress()).getPort();
	}

	@Override
	public void close() {
		this.listener.close()
		             .awaitUninterruptibly();
		this.boss.shutdownGracefully(0,
		                             2,
		                             TimeUnit.SECONDS)
		         .awaitUninterruptibly();
		this.worker.shutdownGracefully(0,
		                               2,
		                               TimeUnit.SECONDS)
		           .awaitUninterruptibly();
	}
}
