package com.inzent.apim.utilities.http.caller.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP/1.1, one connection per exchange, no retries/redirects/decompression.
 * Manual reads bound inbound buffering to a single 16 KiB socket read. The worker
 * performs body file I/O; the event loop only copies and releases decoded chunks.
 */
public final class NettyTransport implements AutoCloseable {
    private final Path directory;
    private final long maximum;
    private final SSLContext sslContext;
    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore permits = new Semaphore(256);
    // Platform DNS can ignore interruption. Keep it off Netty and bound retained threads/tasks.
    private final ExecutorService resolver = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),task -> {Thread thread=new Thread(task,"caller-dns");thread.setDaemon(true);return thread;},
            new ThreadPoolExecutor.AbortPolicy());
    public NettyTransport(Path temporaryDirectory, long maxResponseBytes) {
        this(temporaryDirectory,maxResponseBytes,defaultContext());
    }
    public NettyTransport(Path temporaryDirectory, long maxResponseBytes, SSLContext sslContext) {
        if (maxResponseBytes < 0) throw new IllegalArgumentException("Negative response limit");
        directory=Objects.requireNonNull(temporaryDirectory); maximum=maxResponseBytes;this.sslContext=Objects.requireNonNull(sslContext);
    }
    private static SSLContext defaultContext() {
        try { return SSLContext.getDefault(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public TransportResponse execute(TransportRequest request, Duration timeout) throws Exception {
        validate(request);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout required");
        long budget=timeout.toNanos();
        if(budget>Long.MAX_VALUE/2)throw new IllegalArgumentException("Timeout too large");
        if (closed.get()) throw new IllegalStateException("Transport closed");
        if (!permits.tryAcquire()) throw new IOException("Transport connection limit reached");
        long start=System.nanoTime(), deadline=start+budget;
        Channel channel=null;
        BodyStore response=null;
        boolean delivered=false;
        Throwable failure=null;
        try {
            response=BodyStore.create(directory,256*1024,maximum);
            URI uri=request.uri(); boolean tls="https".equalsIgnoreCase(uri.getScheme());
            int port=uri.getPort()==-1?(tls?443:80):uri.getPort();
            Receiver receiver=new Receiver();
            Bootstrap bootstrap=new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.AUTO_READ,false)
                    .option(ChannelOption.RCVBUF_ALLOCATOR,new FixedRecvByteBufAllocator(16384).maxMessagesPerRead(1))
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,(int)Math.max(1,Math.min(10000,timeout.toMillis())))
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            if (tls) {
                                SSLEngine engine=sslContext.createSSLEngine(uri.getHost(),port);engine.setUseClientMode(true);
                                SSLParameters params=engine.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");engine.setSSLParameters(params);
                                ch.pipeline().addLast(new SslHandler(engine));
                            }
                            ch.pipeline().addLast(new HttpClientCodec(4096,8192,8192,true),receiver);
                        }
                    });
            Future<InetAddress> lookup=resolver.submit(()->InetAddress.getByName(uri.getHost()));
            InetAddress address;
            try { address=lookup.get(remaining(deadline),TimeUnit.NANOSECONDS); }
            catch (ExecutionException e) {if(e.getCause() instanceof Exception cause)throw cause;throw new IOException(e.getCause());}
            finally {lookup.cancel(true);}
            ChannelFuture connecting=bootstrap.connect(new InetSocketAddress(address,port));channel=connecting.channel();channels.add(channel);
            if (closed.get()) throw new IOException("Transport closed");
            await(connecting,deadline);
            if(tls) {channel.read();await(channel.pipeline().get(SslHandler.class).handshakeFuture(),deadline);receiver.events.clear();}
            String target=uri.getRawPath();if(target==null||target.isEmpty()) target="/";
            if(uri.getRawQuery()!=null)target+="?"+uri.getRawQuery();
            var message=new DefaultHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.valueOf(request.method()),target);
            request.headers().forEach((name,values)-> {
                if (!Set.of("host","content-length","transfer-encoding","connection","expect","upgrade").contains(name.toLowerCase(Locale.ROOT))) message.headers().add(name,values);
            });
            String host=uri.getHost();if(host.indexOf(':')>=0&&!host.startsWith("["))host="["+host+"]";
            message.headers().set(HttpHeaderNames.HOST,host+((tls&&port==443)||(!tls&&port==80)?"":":"+port));
            message.headers().set(HttpHeaderNames.CONNECTION,HttpHeaderValues.CLOSE);
            if(request.chunked())HttpUtil.setTransferEncodingChunked(message,true);
            else HttpUtil.setContentLength(message,request.body().size());
            await(channel.writeAndFlush(message),deadline);
            try(InputStream input=request.body().openStream()) {
                byte[] block=new byte[8192];int n;
                while((n=input.read(block))!=-1) {
                    remaining(deadline);
                    await(channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(Arrays.copyOf(block,n)))),deadline);
                }
            }
            await(channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT),deadline);
            channel.read(); int status=0;Map<String,List<String>> headers=Map.of();
            while(true) {
                Object event=receiver.events.poll(remaining(deadline),TimeUnit.NANOSECONDS);
                if(event==null)throw new TimeoutException("HTTP response timeout");
                if(event instanceof Throwable error) {if(error instanceof Exception e)throw e;throw new IOException(error);}
                if(event instanceof Head head) {status=head.status;headers=head.headers;}
                else if(event instanceof byte[] bytes) response.write(bytes,0,bytes.length);
                else if(event==Signal.READ_COMPLETE)channel.read();
                else if(event==Signal.DONE) {
                    if(status==0)throw new IOException("No final HTTP response");
                    response.finish();delivered=true;
                    return new TransportResponse(status,headers,response,System.nanoTime()-start,request.body().size());
                }
            }
        } catch(Exception|Error e) {
            failure=e;throw e;
        } finally {
            if(channel!=null)channel.close().awaitUninterruptibly();
            permits.release();
            if(!delivered&&response!=null)try{response.close();}catch(IOException e){if(failure==null)throw e;failure.addSuppressed(e);}
        }
    }
    private static long remaining(long deadline) throws TimeoutException, InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("HTTP exchange cancelled");
        long left=deadline-System.nanoTime();if(left<=0)throw new TimeoutException("HTTP exchange timeout");return left;
    }
    private static void await(io.netty.util.concurrent.Future<?> future,long deadline) throws Exception {
        if(!future.await(remaining(deadline),TimeUnit.NANOSECONDS))throw new TimeoutException("HTTP exchange timeout");
        if(!future.isSuccess()) {Throwable cause=future.cause();if(cause instanceof Exception e)throw e;throw new IOException(cause);}
    }
    private static void validate(TransportRequest request) {
        URI uri=request.uri();
        if(!Set.of("http","https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getPort()==0 || uri.getPort()>65535)
            throw new IllegalArgumentException("Invalid HTTP URI");
        if(!request.method().matches("[A-Z]+"))throw new IllegalArgumentException("Invalid HTTP method");
        if(Set.of("GET","HEAD").contains(request.method()) && (request.body().size()!=0 || request.chunked() || request.headers().keySet().stream().anyMatch(k->k.equalsIgnoreCase("content-encoding"))))
            throw new IllegalArgumentException("GET/HEAD require empty identity nonchunked payload");
        HttpHeaders validation=new DefaultHttpHeaders();
        request.headers().forEach((name,values)-> {if(values.isEmpty())throw new IllegalArgumentException("Empty header values");validation.add(name,values);});
    }
    private enum Signal { READ_COMPLETE,DONE }
    private record Head(int status,Map<String,List<String>> headers) { }
    private static final class Receiver extends SimpleChannelInboundHandler<HttpObject> {
        private final BlockingQueue<Object> events=new ArrayBlockingQueue<>(16384);
        private boolean finalResponse,done;
        @Override protected void channelRead0(ChannelHandlerContext ctx,HttpObject object) {
            if(!object.decoderResult().isSuccess()) {events.add(new IOException("Malformed HTTP response",object.decoderResult().cause()));ctx.close();return;}
            if(object instanceof HttpResponse response) {
                int code=response.status().code();
                if(code==101) {events.add(new IOException("HTTP protocol upgrade unsupported"));ctx.close();return;}
                finalResponse=code>=200;
                if(finalResponse) {
                    Map<String,List<String>> headers=new LinkedHashMap<>();
                    for(String name:response.headers().names())headers.put(name.toLowerCase(Locale.ROOT),List.copyOf(response.headers().getAll(name)));
                    events.add(new Head(code,Collections.unmodifiableMap(headers)));
                }
            }
            if(finalResponse && object instanceof HttpContent content) {
                if(content.content().isReadable()) {byte[] bytes=new byte[content.content().readableBytes()];content.content().readBytes(bytes);events.add(bytes);}
                if(content instanceof LastHttpContent) {done=true;events.add(Signal.DONE);}
            }
        }
        @Override public void channelReadComplete(ChannelHandlerContext ctx) { if(!done)events.add(Signal.READ_COMPLETE); }
        @Override public void channelInactive(ChannelHandlerContext ctx) {if(!done)events.offer(new EOFException("Connection closed before complete HTTP response"));}
        @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause) {
            if(!events.offer(cause)){events.clear();events.offer(new IOException("Inbound buffer capacity exceeded",cause));}
            ctx.close();
        }
    }
    @Override public void close() {
        if(closed.compareAndSet(false,true)) {
            resolver.shutdownNow();channels.close().awaitUninterruptibly();group.shutdownGracefully(0,5,TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }
}
