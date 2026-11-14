package com.inzent.apim.utilities.http.caller.script;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ScriptServiceTest {
    @Test void changesNestedMapsAndTextBodyWithoutExposingHostObjects() {
        var vars = new LinkedHashMap<String,Object>();
        var headers = new LinkedHashMap<String,Object>();
        var request = new LinkedHashMap<String,Object>(Map.of("headers", headers, "body", "before"));
        var exchange = new LinkedHashMap<String,Object>(Map.of("request", request));
        var context = new LinkedHashMap<String,Object>(Map.of("worker", new LinkedHashMap<>(Map.of("vars", vars)), "exchange", exchange));
        try (var service = new ScriptService()) {
            service.execute("worker.vars.put('token', 'abc'); exchange.request.headers.put('X-Token', worker.vars.get('token')); exchange.request.body = 'after'; exchange.assertSuccess(false, 'business');", context, Duration.ofSeconds(1));
        }
        assertEquals("abc", vars.get("token"));
        assertEquals("abc", headers.get("X-Token"));
        assertEquals("after", request.get("body"));
        assertEquals(false, exchange.get("success"));
        assertEquals("business", exchange.get("reason"));
    }
    @Test void rejectsForbiddenSyntaxAndArbitraryObjects() {
        try (var service = new ScriptService()) {
            for (String source : List.of("while(true) {}", "for(var i : [1]) {}", "new('java.io.File', 'x')", "#pragma jexl.import java.lang\n1", "worker.getClass()", "worker.class", "worker.toString()")) {
                assertThrows(RuntimeException.class, () -> service.validate(source), source);
            }
            assertThrows(RuntimeException.class, () -> service.execute("x", new HashMap<>(Map.of("x", Thread.currentThread())), Duration.ofSeconds(1)));
        }
    }
    @Test void immutableReadOnlyMetadataRemainsReadable() {
        var context = new LinkedHashMap<String,Object>(Map.of("run", Map.of("id", "run-id"), "worker", new LinkedHashMap<>(Map.of("vars", new LinkedHashMap<>()))));
        try (var service = new ScriptService()) {
            service.execute("worker.vars.put('run', run.id)", context, Duration.ofSeconds(1));
        }
        assertEquals("run-id", ((Map<?,?>)((Map<?,?>)context.get("worker")).get("vars")).get("run"));
    }
    @Test void failuresDoNotCommitAndOversizedValuesAreRejected() {
        var vars = new LinkedHashMap<String,Object>();
        var context = new LinkedHashMap<String,Object>(Map.of("worker", new LinkedHashMap<>(Map.of("vars", vars))));
        try(var service=new ScriptService()) {
            assertThrows(RuntimeException.class, () -> service.execute("worker.vars.put('a', 1); missing.nope()", context, Duration.ofSeconds(1)));
            assertTrue(vars.isEmpty());
            assertThrows(RuntimeException.class, () -> service.execute("worker.vars.put('a', 1);".repeat(1000), context, Duration.ofNanos(1)));
            assertTrue(vars.isEmpty());
            assertThrows(RuntimeException.class, () -> service.execute("x", new LinkedHashMap<>(Map.of("x", "가".repeat(400000))), Duration.ofSeconds(1)));
            assertThrows(RuntimeException.class, () -> service.validate("var x = [1, 2]"));
        }
    }
    @Test void rejectsExpandingSharedGraphsBeforeCloning() {
        Object value="a".repeat(1024);
        for(int i=0;i<5;i++) value=Collections.nCopies(100,value);
        var context=new LinkedHashMap<String,Object>();context.put("x",value);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            try(var service=new ScriptService()) {assertThrows(RuntimeException.class, () -> service.execute("x",context,Duration.ofSeconds(1)));}
        });
    }
    @Test void concurrentWorkerHooksWaitForBoundedExecutorSlots() throws Exception {
        try(var service=new ScriptService()) {
            String script="worker.vars.put('n', 1);".repeat(1000);
            service.validate(script);
            var callers=Executors.newFixedThreadPool(12);
            var start=new CountDownLatch(1);
            try {
                var futures=new ArrayList<Future<?>>();
                for(int i=0;i<12;i++) futures.add(callers.submit(()->{
                    try {start.await();} catch(InterruptedException e){throw new RuntimeException(e);}
                    var vars=new LinkedHashMap<String,Object>();
                    var context=new LinkedHashMap<String,Object>(Map.of("worker",new LinkedHashMap<>(Map.of("vars",vars))));
                    service.execute(script,context,Duration.ofSeconds(10));
                    assertEquals(1,vars.get("n"));
                }));
                start.countDown();
                for(var future:futures)future.get(15,TimeUnit.SECONDS);
            } finally {callers.shutdownNow();}
        }
    }
    @Test void metadataMutationRejectsEntireCommitAndListsRemainReadable() {
        var vars=new LinkedHashMap<String,Object>();
        var context=new LinkedHashMap<String,Object>();
        context.put("worker",new LinkedHashMap<>(Map.of("vars",vars)));
        context.put("run",Map.of("id","original", "tags",List.of("first","second")));
        try(var service=new ScriptService()) {
            assertThrows(RuntimeException.class,()->service.execute("worker.vars.put('leak', 1); run.id='changed'",context,Duration.ofSeconds(1)));
            assertTrue(vars.isEmpty(), "readonly metadata validation must precede all copyback");
            assertEquals("original",((Map<?,?>)context.get("run")).get("id"));
            service.execute("worker.vars.put('tag',run.tags[1])",context,Duration.ofSeconds(1));
            assertEquals("second",vars.get("tag"));
        }
    }
    @Test void enforcesInputLimitsTimeoutAndClose() {
        var service = new ScriptService();
        assertThrows(RuntimeException.class, () -> service.validate("x".repeat(32769)));
        assertThrows(RuntimeException.class, () -> service.execute("x = 1", new HashMap<>(), Duration.ZERO));
        service.close();
        assertThrows(RuntimeException.class, () -> service.execute("x = 1", new HashMap<>(), Duration.ofSeconds(1)));
    }
}
