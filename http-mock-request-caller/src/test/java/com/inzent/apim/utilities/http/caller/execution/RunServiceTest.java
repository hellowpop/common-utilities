package com.inzent.apim.utilities.http.caller.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.config.CallerProperties;
import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RunServiceTest {
    @TempDir Path data;
    CallerStore store;RunService service;HttpServer server;
    volatile CountDownLatch beforeFinalWrite,allowFinalWrite;
    @BeforeEach void setup()throws Exception{
        store=new CallerStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")),new ObjectMapper().findAndRegisterModules(),data){
            @Override public void saveRun(Map<String,Object> value){
                if(beforeFinalWrite!=null&&Set.of("COMPLETED","FAILED","CANCELLED").contains(value.get("status"))){beforeFinalWrite.countDown();try{allowFinalWrite.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                super.saveRun(value);
            }
        };store.initialize();
        service=new RunService(store,new CallerProperties(data.toString(),2,8,1024*1024,1000,1024*1024*16,7));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));
    }
    @AfterEach void cleanup(){if(service!=null)service.close();if(server!=null)server.stop(0);}
    Scenario scenario(){Scenario s=Scenario.example();s.endpointBaseUrl="http://127.0.0.1:"+server.getAddress().getPort();s.maxWorkers=2;s.paths.get(0).payload.contentLength=List.of(64L,128L);return s;}
    Map<String,Object> await(String id)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){var r=service.progress(id);if(Set.of("COMPLETED","FAILED","CANCELLED","INTERRUPTED").contains(r.get("status")))return r;Thread.sleep(20);}
        fail("run did not terminate: "+service.progress(id));return null;
    }
    @Test void executesCartesianHooksAndRetainsFailuresOnly()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server.createContext("/",e->{try{assertEquals("token",e.getRequestHeaders().getFirst("X-Token"));assertEquals("override",new String(e.getRequestBody().readAllBytes()));calls.incrementAndGet();byte[] b="reply".getBytes();e.sendResponseHeaders(201,b.length);e.getResponseBody().write(b);}finally{e.close();}});server.start();
        Scenario s=scenario();s.workerHooks.pre="worker.vars.put('token','token');";s.paths.get(0).requestHooks.pre="exchange.request.setHeader('X-Token',worker.vars.get('token')); exchange.request.body='override';";
        s.paths.get(0).requestHooks.post="exchange.assertSuccess(exchange.response.status == 201, 'status');";
        var a=store.createScenario(s);String id=String.valueOf(service.start(a.id,null).get("id"));var run=await(id);
        assertEquals("COMPLETED",run.get("status"));assertEquals(4L,((Number)run.get("succeeded")).longValue());assertEquals(4,calls.get());
        var results=store.results(id,null,null,0,20);assertEquals(4,results.size());assertNull(results.get(0).get("responseArtifactId"));
    }
    @Test void cancellationAccountsForWorkersNotYetRampedUp()throws Exception{
        CountDownLatch received=new CountDownLatch(1);server.createContext("/",e->{received.countDown();try{Thread.sleep(2000);}catch(Exception ignored){}e.close();});server.start();
        Scenario s=scenario();s.rampUpSeconds=10;s.runTimeoutMillis=20000;s.requestTimeoutMillis=15000;
        var a=store.createScenario(s);String id=String.valueOf(service.start(a.id,"same").get("id"));assertEquals(id,service.start(a.id,"same").get("id"));
        assertThrows(CallerStore.Conflict.class,()->service.start(a.id,null));
        assertTrue(received.await(5,TimeUnit.SECONDS));service.cancel(id);var run=await(id);
        assertEquals("CANCELLED",run.get("status"));
        assertEquals(((Number)run.get("planned")).longValue(),((Number)run.get("completed")).longValue()+((Number)run.get("skipped")).longValue());
        assertEquals(0L,((Number)run.get("inFlight")).longValue());
    }
    @Test void hookFailuresRunPostAndReportBusinessFailure()throws Exception{
        server.createContext("/",e->{e.sendResponseHeaders(500,-1);e.close();});server.start();
        Scenario s=scenario();s.maxWorkers=1;s.bodyRetention="ALL";s.logging.enabled=true;
        s.paths.get(0).pathHooks.pre="worker.vars.put('path','entered');";
        s.paths.get(0).requestHooks.post="exchange.assertSuccess(false,'forced');";
        s.scenarioHooks.post="run.id;";
        var a=store.createScenario(s);String id=String.valueOf(service.start(a.id,null).get("id"));var run=await(id);
        assertEquals("COMPLETED",run.get("status"));assertEquals("WITH_FAILURES",run.get("outcome"));assertEquals(2L,((Number)run.get("failed")).longValue());
        assertTrue(Files.exists(data.resolve("logs").resolve(id).resolve("control.log")));
        assertNotNull(store.results(id,null,null,0,20).get(0).get("requestArtifactId"));
    }
    @Test void lateCancellationCannotRevertFinalStatus()throws Exception{
        server.createContext("/",e->{e.sendResponseHeaders(204,-1);e.close();});server.start();
        Scenario s=scenario();s.maxWorkers=1;
        beforeFinalWrite=new CountDownLatch(1);allowFinalWrite=new CountDownLatch(1);
        var a=store.createScenario(s);String id=String.valueOf(service.start(a.id,null).get("id"));
        try{assertTrue(beforeFinalWrite.await(5,TimeUnit.SECONDS));assertEquals("COMPLETED",service.cancel(id).get("status"));}finally{allowFinalWrite.countDown();}
        assertEquals("COMPLETED",await(id).get("status"));
    }
}
