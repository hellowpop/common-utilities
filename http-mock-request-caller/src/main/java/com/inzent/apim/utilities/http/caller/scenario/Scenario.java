package com.inzent.apim.utilities.http.caller.scenario;

import java.util.*;
import com.inzent.apim.utilities.http.payload.*;

/** Mutable JSON input only; each run receives a deep, independently validated snapshot. */
public class Scenario {
    public String id;
    public long version;
    public String title="HTTP scenario";
    public String description="";
    public String endpointBaseUrl="http://localhost:8080";
    public boolean enabled=true;
    public int maxWorkers=4;
    public int loopCount=1;
    public double rampUpSeconds=0;
    public long requestTimeoutMillis=30000;
    public long runTimeoutMillis=900000;
    public String selectionMode="SHUFFLED_ALL";
    public Long seed;
    public String bodyRetention="FAILURES";
    public Hooks scenarioHooks=new Hooks();
    public Hooks workerHooks=new Hooks();
    public Logs logging=new Logs();
    public List<RequestPath> paths=new ArrayList<>();
    public static Scenario example(){Scenario s=new Scenario();s.paths.add(new RequestPath());return s;}
    public static class Hooks {public String pre=""; public String post="";}
    public static class Logs {
        public boolean enabled=false;
        public long maxFileSize=10*1024*1024;
        public int maxFiles=5;
        public boolean gzip=true;
    }
    public static class RequestPath {
        public String id=UUID.randomUUID().toString();
        public String name="request";
        public String requestUrl;
        public String requestPath="/";
        public String method="POST";
        public Map<String,List<String>> headers=new LinkedHashMap<>();
        public Payload payload=new Payload();
        public Hooks pathHooks=new Hooks();
        public Hooks requestHooks=new Hooks();
        public int successStatusMin=200;
        public int successStatusMax=299;
    }
    public static class Payload {
        public List<Boolean> chunked=List.of(false);
        public List<ContentType> contentType=List.of(ContentType.APPLICATION_JSON);
        public List<ContentEncoding> contentEncoding=List.of(ContentEncoding.IDENTITY);
        public List<Long> contentLength=List.of(4096L);
        public List<Long> blockSize=List.of(128L);
    }
}
