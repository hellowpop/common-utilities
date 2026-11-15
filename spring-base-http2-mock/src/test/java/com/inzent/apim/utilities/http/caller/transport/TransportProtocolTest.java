package com.inzent.apim.utilities.http.caller.transport;

import com.inzent.apim.utilities.http.caller.payload.*;
import com.inzent.apim.utilities.http.payload.*;
import com.sun.net.httpserver.*;
import io.netty.buffer.*;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TransportProtocolTest {
    @TempDir Path directory;
    @Test void allPayloadTypesAndEncodingsAndRawOverridesRoundTrip() throws Exception {
        var adapter=new PayloadAdapter(32,100000);
        for(ContentType type:ContentType.values()) for(ContentEncoding encoding:ContentEncoding.values()) {
            var spec=new PayloadSpec(true,type,encoding,4096,16);
            try(var original=adapter.prepare(new PayloadSpec(false,type,ContentEncoding.IDENTITY,4096,16),directory);
                var encoded=adapter.prepare(spec,directory);
                var overridden=adapter.override(spec,"raw body override".getBytes(),directory)) {
                assertArrayEquals(original.body().readBytes(100000),decode(encoding,encoded.body().readBytes(100000)),type+"/"+encoding);
                assertArrayEquals("raw body override".getBytes(),decode(encoding,overridden.body().readBytes(100000)));
                assertEquals(type.mediaType(),encoded.headers().get("content-type").get(0));
                assertFalse(encoded.headers().containsKey("content-length"));
            }
        }
        try(var files=Files.list(directory)){assertEquals(0,files.count());}
    }
    private byte[] decode(ContentEncoding encoding,byte[] bytes) throws Exception {
        if(encoding==ContentEncoding.IDENTITY)return bytes;
        ChannelHandler decoder=switch(encoding) {
            case GZIP -> new JdkZlibDecoder(ZlibWrapper.GZIP);
            case DEFLATE -> new JdkZlibDecoder(ZlibWrapper.ZLIB);
            case BR -> new BrotliDecoder();case ZSTD -> new ZstdDecoder();case SNAPPY -> new SnappyFrameDecoder();
            default -> throw new AssertionError();
        };
        EmbeddedChannel channel=new EmbeddedChannel(decoder);
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(bytes));channel.finish();
            ByteArrayOutputStream output=new ByteArrayOutputStream();ByteBuf buffer;
            while((buffer=channel.readInbound())!=null)try{buffer.readBytes(output,buffer.readableBytes());}finally{buffer.release();}
            return output.toByteArray();
        } finally {channel.finishAndReleaseAll();}
    }
    @Test void tlsTrustAndHostnameVerifiedWithExplicitTrustContext() throws Exception {
        Path keyfile=directory.resolve("test.p12");
        String keytool=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"keytool.exe":"keytool";
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",keytool).toString(),"-genkeypair","-alias","test","-keyalg","RSA","-storetype","PKCS12","-keystore",keyfile.toString(),"-storepass","changeit","-keypass","changeit","-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2","-noprompt").redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());assertEquals(0,process.waitFor(),output);
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(keyfile)){keys.load(in,"changeit".toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"changeit".toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        SSLContext serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        SSLContext trusted=SSLContext.getInstance("TLS");trusted.init(null,tm.getTrustManagers(),null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/",exchange->{exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
        try(var body=BodyStore.create(directory,0,0);var strict=new NettyTransport(directory,100);var custom=new NettyTransport(directory,100,trusted)) {
            body.finish();int port=server.getAddress().getPort();
            assertThrows(Exception.class,()->strict.execute(new TransportRequest(URI.create("https://localhost:"+port),"POST",Map.of(),body,false),Duration.ofSeconds(3)));
            try(var response=custom.execute(new TransportRequest(URI.create("https://localhost:"+port),"POST",Map.of(),body,false),Duration.ofSeconds(3))){assertEquals(204,response.status());}
            assertThrows(Exception.class,()->custom.execute(new TransportRequest(URI.create("https://127.0.0.1:"+port),"POST",Map.of(),body,false),Duration.ofSeconds(3)));
        } finally {server.stop(0);}
    }
    @Test void partialResponseFailsAndInterruptClosesConnection() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var transport=new NettyTransport(directory,100000);var body=BodyStore.create(directory,0,0)) {
            body.finish();ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> sender=executor.submit(()->{try(var socket=server.accept()){socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial".getBytes());}catch(IOException e){throw new UncheckedIOException(e);}});
            try {assertThrows(Exception.class,()->transport.execute(new TransportRequest(URI.create("http://localhost:"+server.getLocalPort()),"POST",Map.of(),body,false),Duration.ofSeconds(3)));sender.get();}finally{executor.shutdownNow();}
        }
        CountDownLatch accepted=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var transport=new NettyTransport(directory,100);var body=BodyStore.create(directory,0,0)) {
            body.finish();Thread worker=new Thread(()->{try{transport.execute(new TransportRequest(URI.create("http://localhost:"+server.getLocalPort()),"POST",Map.of(),body,false),Duration.ofSeconds(30));}catch(Throwable e){failure.set(e);}});
            worker.start();try(var socket=server.accept()) {accepted.countDown();worker.interrupt();worker.join(3000);assertFalse(worker.isAlive());assertInstanceOf(InterruptedException.class,failure.get());}
        }
        try(var files=Files.list(directory)){assertEquals(0,files.count());}
    }
    @Test void informationalAndNoBodyAndRedirectResponsesDoNotRetry() throws Exception {
        for(String response:List.of("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n","HTTP/1.1 304 Not Modified\r\nContent-Length: 12\r\n\r\n","HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 0\r\n\r\n")) {
            try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var transport=new NettyTransport(directory,100);var body=BodyStore.create(directory,0,0)) {
                body.finish();Thread peer=new Thread(()->{try(var socket=server.accept()){socket.getInputStream().read(new byte[8192]);socket.getOutputStream().write(response.getBytes());}catch(IOException e){throw new UncheckedIOException(e);}});peer.start();
                try(var result=transport.execute(new TransportRequest(URI.create("http://localhost:"+server.getLocalPort()),"POST",Map.of(),body,false),Duration.ofSeconds(3))) {assertEquals(0,result.body().size());assertTrue(Set.of(204,304,302).contains(result.status()));}
                peer.join(1000);
            }
        }
    }
    @Test void streamedResponsePromotesToDiskAndMovesOwnership() throws Exception {
        byte[] bytes=new byte[600000];new Random(42).nextBytes(bytes);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.sendResponseHeaders(200,0);try{exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        Path destination=directory.resolve("retained.bin");
        try(var transport=new NettyTransport(directory,1000000);var body=BodyStore.create(directory,0,0)) {
            body.finish();
            try(var response=transport.execute(new TransportRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"POST",Map.of(),body,false),Duration.ofSeconds(5))) {
                assertEquals(bytes.length,response.body().size());response.body().moveTo(destination);
            }
            assertArrayEquals(bytes,Files.readAllBytes(destination));
        } finally {server.stop(0);}
        try(var files=Files.list(directory)){assertEquals(List.of(destination),files.toList());}
    }
}
