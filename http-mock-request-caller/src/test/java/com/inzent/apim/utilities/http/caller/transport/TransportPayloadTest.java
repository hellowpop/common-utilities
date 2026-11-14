package com.inzent.apim.utilities.http.caller.transport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.inzent.apim.utilities.http.caller.payload.*;
import com.inzent.apim.utilities.http.payload.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
class TransportPayloadTest {
 @TempDir Path dir;
 @Test void bodyPromotionLimitAndOwnership() throws Exception {
   Path target=dir.resolve("saved.bin");
   try(BodyStore b=BodyStore.create(dir,2,5)) {
     b.write(new byte[]{1,2,3},0,3); assertThrows(java.io.IOException.class,()->b.write(new byte[3],0,3));
     b.finish(); assertArrayEquals(new byte[]{1,2,3}, b.readBytes(5));
     assertThrows(java.io.IOException.class,()->b.readBytes(2)); b.moveTo(target);
   }
   assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(target));
   try(var paths=Files.list(dir)){assertEquals(1, paths.count());}
 }
 @Test void generatedAndOverriddenEncodedBodiesSurviveCallback() throws Exception {
   var adapter=new PayloadAdapter(); var spec=new PayloadSpec(false,ContentType.APPLICATION_JSON,ContentEncoding.GZIP,100,8);
   try(var p=adapter.prepare(spec,dir);var in=new GZIPInputStream(p.body().openStream())) {assertEquals(100,in.readAllBytes().length);}
   try(var p=adapter.override(spec,"{\"x\":1}".getBytes(),dir);var in=new GZIPInputStream(p.body().openStream())) {
     assertEquals("{\"x\":1}",new String(in.readAllBytes())); assertEquals("gzip",p.headers().get("content-encoding").get(0));
   }
 }
 @Test void realFixedAndChunkedHttpPreserveEncodedWireBody() throws Exception {
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/echo",x->{byte[] body=x.getRequestBody().readAllBytes();x.getResponseHeaders().add("X-Framing",x.getRequestHeaders().getFirst("Transfer-Encoding")==null?"fixed":"chunked");x.sendResponseHeaders(201,body.length);x.getResponseBody().write(body);x.close();});server.start();
   try(var transport=new NettyTransport(dir,10000)) {
     for(boolean chunked:List.of(false,true))try(var p=new PayloadAdapter().prepare(new PayloadSpec(chunked,ContentType.TEXT_PLAIN,ContentEncoding.GZIP,100,8),dir);
       var r=transport.execute(new TransportRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/echo?q=1"),"POST",p.headers(),p.body(),chunked),Duration.ofSeconds(5))) {
       assertEquals(201,r.status()); assertEquals(p.body().size(),r.sentBytes());assertArrayEquals(p.body().readBytes(10000),r.body().readBytes(10000));
       assertEquals(chunked?"chunked":"fixed",r.headers().get("x-framing").get(0));
     }
   } finally {server.stop(0);}
 }
 @Test void timeoutAndResponseLimitCleanPartialFiles() throws Exception {
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/large",x->{x.sendResponseHeaders(200,10000);try{x.getResponseBody().write(new byte[10000]);}finally{x.close();}});
   server.createContext("/slow",x->{try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();}x.close();});server.start();
   try(var transport=new NettyTransport(dir,10);var b=BodyStore.create(dir,4,10)) {
     b.finish();
     for(String path:List.of("/large","/slow"))assertThrows(Exception.class,()->transport.execute(new TransportRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path),"POST",Map.of(),b,false),Duration.ofMillis(100)));
   }finally{server.stop(0);}
   try(var paths=Files.list(dir)){assertEquals(0,paths.count());}
 }
}
