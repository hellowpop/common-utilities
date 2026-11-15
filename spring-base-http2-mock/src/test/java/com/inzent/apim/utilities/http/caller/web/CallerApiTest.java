package com.inzent.apim.utilities.http.caller.web;

import com.fasterxml.jackson.databind.*;
import com.inzent.apim.utilities.http2mock.MockApplication;
import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=MockApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.ssl.enabled=false", "mock.http.port=0", "mock.services.netty.enabled=false", "mock.services.response.enabled=false"})
class CallerApiTest {
    @Test void callerConsoleRetainsSecurityHeadersAndHostRootIsPreserved() throws Exception {
        for (String path : List.of("/caller/", "/caller/app.js")) {
            var response = send(path, "GET", null, null);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Security-Policy").isPresent(), path);
        }
        var root = send("/", "GET", null, null);
        assertTrue(root.body().contains("Protocol Lab"));
        assertTrue(root.headers().firstValue("Content-Security-Policy").isEmpty());
    }
    @Test void yamlDownloadImportCreatesDetachedValidatedDraftAndPreservesAllSettings()throws Exception{
        String token=json.readTree(send("/api/csrf","GET",null,null).body()).get("token").asText();
        Scenario s=Scenario.example();s.title="YAML 복원";s.description="first\nsecond: # value";
        s.id="original";s.version=9;s.executionMode="DURATION";s.durationSeconds=3;s.seed=Long.MAX_VALUE;
        s.paths.get(0).headers.put("X-Tag",List.of("001","true"));s.paths.get(0).delay.mode="RANDOM";s.paths.get(0).delay.minMillis=10;s.paths.get(0).delay.maxMillis=20;
        s.paths.get(0).requestHooks.pre="worker.vars.put('x', '한글');\n// next";
        var downloaded=send("/api/scenarios/export","POST",json.writeValueAsString(s),token);
        assertEquals(200,downloaded.statusCode(),downloaded.body());
        assertTrue(downloaded.headers().firstValue("Content-Disposition").orElse("").contains(".yml"));
        assertTrue(downloaded.body().contains("executionMode: DURATION"));assertFalse(downloaded.body().contains("!!"));
        var imported=send("/api/scenarios/import","POST",downloaded.body(),token);assertEquals(200,imported.statusCode(),imported.body());
        Scenario draft=json.readValue(imported.body(),Scenario.class);assertNull(draft.id);assertEquals(0,draft.version);
        assertTrue(json.readTree(imported.body()).get("seed").isTextual(),"Browser must receive exact 64-bit seed as text");
        s.id=null;s.version=0;assertEquals(json.valueToTree(s),json.valueToTree(draft));
        var created=send("/api/scenarios","POST",imported.body(),token);assertEquals(201,created.statusCode(),created.body());
        assertNotEquals("original",json.readTree(created.body()).path("id").asText());
        assertEquals(Long.toString(Long.MAX_VALUE),json.readTree(created.body()).path("seed").asText());
    }
    @Test void yamlImportRejectsUnsafeMalformedOrInvalidValuesWithoutPersistence()throws Exception{
        String token=json.readTree(send("/api/csrf","GET",null,null).body()).get("token").asText();
        for(String input:List.of("", "[]", "title: one\ntitle: two", "!!java.net.URL [http://localhost]", "a: &a [*a]", "title: [broken", "---\ntitle: one\n---\ntitle: two", "durationSeconds: 1.9", "paths: []", "x".repeat(1048577))){
            var response=send("/api/scenarios/import","POST",input,token);assertEquals(400,response.statusCode(),input.substring(0,Math.min(input.length(),100))+": "+response.body());
        }
        assertEquals(403,send("/api/scenarios/import","POST","title: test",null).statusCode());
    }
    @Test void fractionalDurationAndDelayCannotBeSilentlyTruncated()throws Exception{
        String token=json.readTree(send("/api/csrf","GET",null,null).body()).get("token").asText();
        Scenario scenario=Scenario.example();scenario.executionMode="DURATION";scenario.durationSeconds=1;
        var body=json.valueToTree(scenario);((com.fasterxml.jackson.databind.node.ObjectNode)body).put("durationSeconds",1.9);
        assertEquals(400,send("/api/scenarios","POST",json.writeValueAsString(body),token).statusCode());
        scenario.executionMode="LOOPS";scenario.paths.get(0).delay.mode="FIXED";body=json.valueToTree(scenario);
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("paths").get(0).get("delay")).put("fixedMillis",-0.5);
        assertEquals(400,send("/api/scenarios","POST",json.writeValueAsString(body),token).statusCode());
    }
    @Test void durationValidationAndReportExposeTimeInsteadOfUnknownRequestCount()throws Exception{
        String token=json.readTree(send("/api/csrf","GET",null,null).body()).get("token").asText();
        Scenario s=Scenario.example();s.title="duration-api";s.endpointBaseUrl="http://127.0.0.1:"+port;s.maxWorkers=1;s.executionMode="DURATION";s.durationSeconds=1;s.loopCount=0;
        s.paths.get(0).method="GET";s.paths.get(0).requestPath="/api/status";s.paths.get(0).payload.contentLength=List.of(0L);s.paths.get(0).delay.mode="FIXED";s.paths.get(0).delay.fixedMillis=100;
        var created=send("/api/scenarios","POST",json.writeValueAsString(s),token);assertEquals(201,created.statusCode(),created.body());String id=json.readTree(created.body()).get("id").asText();
        var valid=send("/api/scenarios/"+id+"/validate","POST","{}",token);assertEquals(200,valid.statusCode(),valid.body());assertTrue(json.readTree(valid.body()).get("planned").isNull());
        var started=send("/api/scenarios/"+id+"/runs","POST","{}",token);assertEquals(202,started.statusCode(),started.body());String runId=json.readTree(started.body()).get("id").asText();
        JsonNode run=null;for(int i=0;i<200;i++){run=json.readTree(send("/api/runs/"+runId,"GET",null,null).body());if(Set.of("COMPLETED","FAILED","CANCELLED").contains(run.path("status").asText()))break;Thread.sleep(20);}
        assertEquals("COMPLETED",run.path("status").asText(),run.toPrettyString());assertTrue(run.get("planned").isNull());
        var report=send("/api/runs/"+runId+"/report","GET",null,null);assertEquals(200,report.statusCode());assertTrue(report.body().contains("시간 기준 (요청 수 미정)"));
    }
    static final Path DATA;
    static {try{DATA=Files.createTempDirectory("caller-api-test-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:api-test;DB_CLOSE_DELAY=-1");r.add("caller.data-directory",DATA::toString);}
    @LocalServerPort int port;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    HttpResponse<String> send(String path,String method,String body,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");if(token!=null)b.header("X-CSRF-Token",token);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void protectsWritesAndSupportsCrudExecutionSearchAndOfflineReport()throws Exception{
        assertEquals(403,send("/api/scenarios","POST","{}",null).statusCode());
        String token=json.readTree(send("/api/csrf","GET",null,null).body()).get("token").asText();
        Scenario s=Scenario.example();s.title="<script>alert('x')</script>";s.endpointBaseUrl="http://127.0.0.1:1";s.maxWorkers=1;s.requestTimeoutMillis=1000;
        var created=send("/api/scenarios","POST",json.writeValueAsString(s),token);assertEquals(201,created.statusCode(),created.body());String id=json.readTree(created.body()).get("id").asText();
        var start=send("/api/scenarios/"+id+"/runs","POST","{}",token);assertEquals(202,start.statusCode(),start.body());String runId=json.readTree(start.body()).get("id").asText();
        JsonNode run=null;for(int i=0;i<200;i++){run=json.readTree(send("/api/runs/"+runId,"GET",null,null).body());if(Set.of("COMPLETED","FAILED","CANCELLED").contains(run.path("status").asText()))break;Thread.sleep(25);}
        assertEquals(1,run.path("completed").asInt());
        var report=send("/api/runs/"+runId+"/report","GET",null,null);assertEquals(200,report.statusCode(),report.body());assertTrue(report.body().contains("&lt;script&gt;"));assertFalse(report.body().contains("<script>alert"));
        assertEquals(1,json.readTree(send("/api/runs?title=alert","GET",null,null).body()).size());
        assertEquals(404,send("/api/runs/"+runId+"/artifacts/not-owned","GET",null,null).statusCode());
        assertEquals(200,send("/","GET",null,null).statusCode());
        assertEquals(400,send("/api/runs?from=bad","GET",null,null).statusCode());
    }
}
