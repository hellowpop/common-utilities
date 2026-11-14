package com.inzent.apim.utilities.http.caller.web;

import com.fasterxml.jackson.databind.*;
import com.inzent.apim.utilities.http.caller.HttpMockRequestCallerApplication;
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

@SpringBootTest(classes=HttpMockRequestCallerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CallerApiTest {
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
