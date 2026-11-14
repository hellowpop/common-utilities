package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.scenario.*;
import com.inzent.apim.utilities.http.caller.payload.*;
import com.inzent.apim.utilities.http.caller.transport.*;
import com.inzent.apim.utilities.http.caller.logging.RunLogManager;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Owns one worker's synchronous scopes; Netty callbacks never execute hooks or touch persistence. */
final class WorkerRunner implements Runnable {
    private final RunService service;private final RunState run;private final RunState.Worker worker;
    private final ResultWriter writer;private final RunLogManager logs;private final String stream;
    private final PayloadAdapter adapter=new PayloadAdapter();
    WorkerRunner(RunService s,RunState r,RunState.Worker w,ResultWriter writer,RunLogManager logs){service=s;run=r;worker=w;this.writer=writer;this.logs=logs;stream=String.format("worker-%04d",w.index);}
    @Override public void run(){
        if(!worker.claimed.compareAndSet(false,true))return;
        worker.thread=Thread.currentThread();worker.status="RUNNING";worker.startedAt=Instant.now().toString();
        try{
            if(run.stopping())return;
            logs.log(stream,"START worker="+worker.index);
            var c=RunService.context(run,worker,null,null,"worker","pre");
            if(!service.hook(run,run.scenario.workerHooks.pre,c,"worker","pre",logs,stream))throw new IllegalStateException("Worker pre failed");
            for(int loop=0;loop<run.scenario.loopCount&&!run.stopping();loop++)for(var path:run.scenario.paths){if(run.stopping())break;path(path,loop);}
        }catch(Throwable e){run.fatal(e);worker.status="FAILED";}
        finally{
            Thread.interrupted();
            try{var c=RunService.context(run,worker,null,null,"worker","post");if(!service.hook(run,run.scenario.workerHooks.post,c,"worker","post",logs,stream))run.fatal(new IllegalStateException("Worker post failed"));logs.log(stream,"END requests="+worker.completed);}
            catch(Throwable e){run.fatal(e);}
            worker.status=run.stopping()?"CANCELLED":"COMPLETED";worker.endedAt=Instant.now().toString();worker.thread=null;worker.done.countDown();Thread.interrupted();
        }
    }
    private void path(Scenario.RequestPath path,int loop){
        Map<String,Object> iteration=new LinkedHashMap<>();iteration.put("pathId",path.id);iteration.put("workerIndex",worker.index);iteration.put("loopIndex",loop);iteration.put("startedAt",Instant.now().toString());
        try{
            var c=RunService.context(run,worker,path,null,"path","pre");
            if(!service.hook(run,path.pathHooks.pre,c,"path:"+path.id,"pre",logs,stream)){iteration.put("pre","FAILED");return;}iteration.put("pre","OK");
            PayloadMatrix matrix=new PayloadMatrix(path.payload);
            for(long i=0;i<matrix.size()&&!run.stopping();i++){
                long index=matrix.index(i,"SHUFFLED_ALL".equals(run.scenario.selectionMode),run.seed^worker.index*104729L^loop*13007L^path.id.hashCode());
                request(path,matrix.at(index),index,loop);
            }
        }finally{
            Thread.interrupted();var c=RunService.context(run,worker,path,null,"path","post");boolean ok=service.hook(run,path.pathHooks.post,c,"path:"+path.id,"post",logs,stream);iteration.put("post",ok?"OK":"FAILED");iteration.put("endedAt",Instant.now().toString());run.iteration(iteration);
        }
    }
    private void request(Scenario.RequestPath path,PayloadSpec spec,long combination,int loop){
        run.requestStarted();long begin=System.nanoTime();String id=UUID.randomUUID().toString();
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("runId",run.id);result.put("pathId",path.id);result.put("pathName",path.name);result.put("workerIndex",worker.index);result.put("loopIndex",loop);result.put("combinationIndex",combination);result.put("payload",spec);result.put("startedAt",Instant.now().toString());
        Map<String,Object> exchange=new LinkedHashMap<>(),request=new LinkedHashMap<>(),response=new LinkedHashMap<>();exchange.put("id",id);exchange.put("request",request);exchange.put("response",response);exchange.put("success",false);
        PreparedPayload prepared=null,replacement=null;TransportResponse received=null;
        long deadline=Math.min(begin+TimeUnit.MILLISECONDS.toNanos(run.scenario.requestTimeoutMillis),run.beginNanos+TimeUnit.MILLISECONDS.toNanos(run.scenario.runTimeoutMillis));
        var watchdog=new DeadlineGuard(service.timer,deadline-System.nanoTime());
        List<Map<String,Object>> artifacts=new ArrayList<>();String stage="PREPARE";
        try{
            if(run.stopping())throw new CancellationException("Run stopping");
            if(Files.getFileStore(service.data.resolve("temp")).getUsableSpace()<spec.contentLength()*3+16L*1024*1024)throw new IOException("Insufficient payload working disk space");
            prepared=adapter.prepare(spec,service.data.resolve("temp").resolve(run.id));
            String originalBody=bodyText(prepared.body(),spec.contentEncoding().name());
            request.put("body",originalBody);request.put("bodyAvailable",originalBody!=null);request.put("bodySize",prepared.body().size());
            request.put("uri",ScenarioValidator.resolve(path.requestUrl==null||path.requestUrl.isBlank()?run.scenario.endpointBaseUrl:path.requestUrl,path.requestPath).toASCIIString());request.put("method",path.method);
            Map<String,Object> extras=new LinkedHashMap<>();path.headers.forEach((k,v)->extras.put(k,new ArrayList<>(v)));request.put("headers",extras);
            stage="SCRIPT_PRE";
            if(!service.hook(run,path.requestHooks.pre,RunService.context(run,worker,path,exchange,"request","pre"),"request:"+id,"pre",logs,stream))throw new IllegalStateException("Request pre hook failed");
            if(run.stopping())throw new CancellationException("Run stopping");
            Object changed=request.get("body");
            if(!Objects.equals(originalBody,changed)){
                if(!(changed instanceof String text))throw new IllegalArgumentException("body override must be a String");byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>1048576)throw new IllegalArgumentException("body override exceeds 1 MiB");
                stage="PREPARE";replacement=adapter.override(spec,bytes,service.data.resolve("temp").resolve(run.id));
            }
            PreparedPayload sent=replacement==null?prepared:replacement;
            stage="VALIDATE";Map<String,List<String>> headerExtras=toHeaders(request.get("headers"));ScenarioValidator.validateHeaders(headerExtras);
            URI uri=URI.create(String.valueOf(request.get("uri")));if(uri.getFragment()!=null||uri.getRawUserInfo()!=null||uri.getHost()==null||!("http".equals(uri.getScheme())||"https".equals(uri.getScheme())))throw new IllegalArgumentException("Invalid final request URI");
            String method=String.valueOf(request.get("method"));if(!Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS").contains(method))throw new IllegalArgumentException("Invalid final method");
            if((method.equals("GET")||method.equals("HEAD"))&&(sent.body().size()!=0||spec.chunked()||!spec.contentEncoding().name().equals("IDENTITY")))throw new IllegalArgumentException("GET/HEAD cannot send body");
            Map<String,List<String>> headers=new LinkedHashMap<>(sent.headers());headers.putAll(headerExtras);result.put("uri",uri.toASCIIString());result.put("method",method);result.put("requestHeaders",headers);result.put("requestBodySize",sent.body().size());
            if(deadline<=System.nanoTime())throw new TimeoutException("Request deadline expired");stage="HTTP";
            received=service.transport.execute(new TransportRequest(uri,method,headers,sent.body(),spec.chunked()),Duration.ofNanos(deadline-System.nanoTime()));
            result.put("status",received.status());result.put("responseHeaders",received.headers());result.put("receivedBytes",received.body().size());result.put("sentBytes",received.sentBytes());result.put("wireNanos",received.elapsedNanos());
            response.put("status",received.status());response.put("headers",new LinkedHashMap<>(received.headers()));response.put("body",bodyText(received.body(),encoding(received.headers())));response.put("bodyAvailable",response.get("body")!=null);response.put("bodySize",received.body().size());
            boolean ok=received.status()>=path.successStatusMin&&received.status()<=path.successStatusMax;exchange.put("success",ok);if(!ok){result.put("errorType","HTTP_STATUS");result.put("error","Unexpected HTTP status "+received.status());}
        }catch(Throwable e){
            exchange.put("success",false);exchange.put("error",RunState.brief(e));result.put("error",RunState.brief(e));
            result.put("errorType",run.stopping()?"CANCELLED":System.nanoTime()>=deadline?"TIMEOUT":stage);
        }finally{
            watchdog.close();Thread.interrupted();
            try{
                var context=RunService.context(run,worker,path,exchange,"request","post");boolean post=service.hook(run,path.requestHooks.post,context,"request:"+id,"post",logs,stream);
                if(!post){exchange.put("success",false);result.put("postError","Request post hook failed");result.putIfAbsent("errorType","SCRIPT_POST");}
                boolean wireSuccess=received!=null&&received.status()>=path.successStatusMin&&received.status()<=path.successStatusMax;
                boolean success=wireSuccess&&Boolean.TRUE.equals(exchange.get("success"))&&post;result.put("success",success);
                if(!success&&exchange.get("reason")!=null){result.put("assertion",exchange.get("reason"));result.putIfAbsent("errorType","ASSERTION");}
                boolean keep="ALL".equals(run.scenario.bodyRetention)||!success&&"FAILURES".equals(run.scenario.bodyRetention);
                PreparedPayload sent=replacement==null?prepared:replacement;
                if(keep&&sent!=null)retain(result,artifacts,"request",sent.body(),"application/octet-stream");
                if(keep&&received!=null){Object view=response.get("body");String original=bodyText(received.body(),encoding(received.headers()));if(view instanceof String text&&!Objects.equals(original,text))result.put("responseViewBody",text);retain(result,artifacts,"response",received.body(),"application/octet-stream");}
                result.put("responseViewHeaders",response.get("headers"));
            }catch(Throwable e){result.put("success",false);result.put("errorType","STORAGE");result.put("error",RunState.brief(e));run.fatal(e);}
            finally{close(received);close(replacement);close(prepared);}
            result.putIfAbsent("success",false);result.put("artifacts",artifacts);result.put("elapsedNanos",System.nanoTime()-begin);result.put("endedAt",Instant.now().toString());
            try{writer.offer(result);}catch(Exception e){Thread.interrupted();try{writer.append(Map.of("type","result","value",result));run.recoveryPending();}catch(Exception failed){run.lostResults();}run.fatal(e);}
            run.result(result);worker.completed++;try{logs.log(stream,"REQUEST "+id+" status="+result.get("status")+" success="+result.get("success")+" elapsedMs="+(System.nanoTime()-begin)/1e6);}catch(Exception e){run.fatal(e);}Thread.interrupted();
        }
    }
    private void retain(Map<String,Object> result,List<Map<String,Object>> artifacts,String kind,BodyStore body,String type)throws Exception{
        if(!run.reserveBody(body.size(),service.properties.runBodyBudget())||!service.storage.reserve(body.size()))throw new IllegalStateException("Run or global artifact budget exceeded");
        String artifactId=UUID.randomUUID().toString();Path destination=service.data.resolve("bodies").resolve(run.id).resolve(artifactId+".bin");Files.createDirectories(destination.getParent());
        long size=body.size();String hash;try(InputStream input=body.openStream()){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[8192];for(int n;(n=input.read(b))>=0;)digest.update(b,0,n);hash=HexFormat.of().formatHex(digest.digest());}
        body.moveTo(destination);artifacts.add(Map.of("id",artifactId,"path",destination.toString(),"size",size,"contentType",type));result.put(kind+"ArtifactId",artifactId);result.put(kind+"BodyHash",hash);
    }
    private void close(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception e){run.fatal(e);}}
    private static String encoding(Map<String,List<String>> headers){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Content-Encoding")).findFirst().map(e->e.getValue().get(0)).orElse("IDENTITY");}
    private static String bodyText(BodyStore body,String encoding)throws IOException{
        // Reserve half of the script context budget for each body, including metadata overhead.
        int limit=384*1024;try(InputStream raw=body.openStream()){
            InputStream input=switch(encoding.toUpperCase(Locale.ROOT)){case "IDENTITY",""->raw;case "GZIP"->new GZIPInputStream(raw);case "DEFLATE"->new InflaterInputStream(raw);default->null;};
            if(input==null)return null;try(input){byte[] b=input.readNBytes(limit+1);return b.length>limit?null:new String(b,StandardCharsets.UTF_8);}
        }
    }
    private static Map<String,List<String>> toHeaders(Object value){
        if(!(value instanceof Map<?,?> m))throw new IllegalArgumentException("headers must be a map");Map<String,List<String>> result=new LinkedHashMap<>();
        m.forEach((k,v)->{if(!(k instanceof String))throw new IllegalArgumentException("header name must be string");if(v instanceof List<?> list){List<String> values=new ArrayList<>();for(Object item:list){if(!(item instanceof String s))throw new IllegalArgumentException("header value must be string");values.add(s);}result.put((String)k,values);}else if(v instanceof String s)result.put((String)k,List.of(s));else throw new IllegalArgumentException("invalid header value");});return result;
    }
}
