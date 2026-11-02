package com.inzent.apim.utilities.netty.http.mockserver.server;

import com.inzent.apim.utilities.netty.http.mockserver.config.ServerConfig;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One lifecycle per instance. close() must be called from outside its I/O event loops. */
public final class NettyHttpMockServer implements HttpMockServer {
    private final ServerConfig config;
    private final ActionRegistry registry;
    private final ChannelGroup children=new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);
    private final AtomicInteger connections=new AtomicInteger();
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private volatile Channel listener;
    private boolean closed;
    public NettyHttpMockServer(ServerConfig config) { this(config,ActionRegistry.discover(config.scanPackages())); }
    public NettyHttpMockServer(ServerConfig config, ActionRegistry registry) {
        this.config=config; this.registry=registry;
        if (!registry.names().contains(config.defaultAction())) throw new IllegalArgumentException("Default action is not registered");
    }
    public synchronized int start() {
        if (closed) throw new IllegalStateException("Server has been closed");
        if (listener!=null) return ((InetSocketAddress)listener.localAddress()).getPort();
        try {
            boss=new NioEventLoopGroup(config.bossThreads());
            workers=new NioEventLoopGroup(config.workerThreads());
            listener=new ServerBootstrap().group(boss,workers).channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG,config.backlog())
                    .option(ChannelOption.SO_REUSEADDR,config.reuseAddress())
                    .childOption(ChannelOption.TCP_NODELAY,config.tcpNoDelay())
                    .childOption(ChannelOption.SO_KEEPALIVE,config.keepAlive())
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,new WriteBufferWaterMark(config.writeBufferLowWaterMark(),config.writeBufferHighWaterMark()))
                    .childHandler(new MockChannelInitializer(config,registry,connections,children))
                    .bind(config.host(),config.port()).syncUninterruptibly().channel();
            return ((InetSocketAddress)listener.localAddress()).getPort();
        } catch (RuntimeException | Error ex) { close(); throw ex; }
    }
    public void awaitClose() throws InterruptedException {
        Channel channel=listener;
        if (channel==null) throw new IllegalStateException("Server has not started");
        channel.closeFuture().sync();
    }
    @Override public synchronized void close() {
        if (closed) return;
        if (inEventLoop(boss) || inEventLoop(workers)) throw new IllegalStateException("Close from outside the I/O event loop");
        closed=true;
        if (listener!=null) listener.close().awaitUninterruptibly();
        children.close().awaitUninterruptibly();
        var workerStop=workers==null ? null : workers.shutdownGracefully(0,config.shutdownTimeoutMs(),TimeUnit.MILLISECONDS);
        var bossStop=boss==null ? null : boss.shutdownGracefully(0,config.shutdownTimeoutMs(),TimeUnit.MILLISECONDS);
        if (workerStop!=null) workerStop.awaitUninterruptibly();
        if (bossStop!=null) bossStop.awaitUninterruptibly();
    }
    private static boolean inEventLoop(EventLoopGroup group) {
        if (group==null) return false;
        for (var executor : group) if (executor.inEventLoop()) return true;
        return false;
    }
}
