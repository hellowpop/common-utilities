package com.inzent.apim.utilities.http.caller.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.config.CallerProperties;
import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import com.inzent.apim.utilities.http.caller.execution.RunService;
import jakarta.servlet.http.HttpSession;
import jakarta.annotation.PreDestroy;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RestController
@RequestMapping("/api")
public final class CallerController {
    private final CallerStore store;private final RunService runs;private final ObjectMapper json;private final Path data;
    private final ScheduledExecutorService snapshots=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"caller-sse");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor sends=new ThreadPoolExecutor(0,16,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"caller-sse-send");t.setDaemon(true);return t;});
    public CallerController(CallerStore store,RunService runs,ObjectMapper json,CallerProperties p){this.store=store;this.runs=runs;this.json=json;data=Path.of(p.dataDirectory()).toAbsolutePath().normalize();}
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(store.encode(value));}
    @GetMapping("/csrf") public ResponseEntity<String> csrf(HttpSession session){String token=(String)session.getAttribute("callerCsrf");if(token==null){token=UUID.randomUUID()+"-"+UUID.randomUUID();session.setAttribute("callerCsrf",token);}return ok(Map.of("token",token));}
    @GetMapping("/example") public ResponseEntity<String> example(){return ok(Scenario.example());}
    @GetMapping("/status") public ResponseEntity<String> status(){return ok(Map.of("degraded",runs.degraded(),"application","http-mock-request-caller"));}
    @GetMapping("/scenarios") public ResponseEntity<String> scenarios(@RequestParam(required=false)String title,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){pages(page,size);return ok(store.scenarios(title,page,size));}
    @GetMapping("/scenarios/{id}") public ResponseEntity<String> scenario(@PathVariable String id){return ok(store.scenario(id));}
    private Scenario input(String body){try{return runs.validate(json.readValue(body,Scenario.class));}catch(java.io.IOException e){throw new IllegalArgumentException("Invalid scenario JSON: "+e.getMessage(),e);}}
    @PostMapping("/scenarios") public ResponseEntity<String> create(@RequestBody String body){var s=store.createScenario(input(body));return ResponseEntity.created(URI.create("/api/scenarios/"+s.id)).contentType(MediaType.APPLICATION_JSON).body(store.encode(s));}
    @PutMapping("/scenarios/{id}") public ResponseEntity<String> update(@PathVariable String id,@RequestBody String body){synchronized(runs){return ok(store.updateScenario(id,input(body)));}}
    @DeleteMapping("/scenarios/{id}") public ResponseEntity<String> delete(@PathVariable String id){synchronized(runs){if(runs.isRunningScenario(id))throw new CallerStore.Conflict("Cannot delete running scenario");store.deleteScenario(id);}return ok(Map.of("deleted",true));}
    @PostMapping("/scenarios/{id}/validate") public ResponseEntity<String> validate(@PathVariable String id){Scenario s=runs.validate(store.scenario(id));return ok(Map.of("valid",true,"planned",new com.inzent.apim.utilities.http.caller.scenario.ScenarioValidator().validate(s)));}
    @PostMapping("/scenarios/{id}/runs") public ResponseEntity<String> start(@PathVariable String id,@RequestHeader(name="Idempotency-Key",required=false)String key){var r=runs.start(id,key);return ResponseEntity.accepted().location(URI.create("/api/runs/"+r.get("id"))).contentType(MediaType.APPLICATION_JSON).body(store.encode(r));}
    @GetMapping("/runs") public ResponseEntity<String> history(@RequestParam(required=false)String scenarioId,@RequestParam(required=false)String title,@RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(required=false)String status,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){pages(page,size);return ok(store.searchRuns(scenarioId,title,from,to,status,page,size));}
    @GetMapping("/runs/{id}") public ResponseEntity<String> progress(@PathVariable String id){return ok(runs.progress(id));}
    @PostMapping("/runs/{id}/cancel") public ResponseEntity<String> cancel(@PathVariable String id){var r=runs.cancel(id);return ResponseEntity.status(terminal(r)?200:202).contentType(MediaType.APPLICATION_JSON).body(store.encode(r));}
    @GetMapping("/runs/{id}/requests") public ResponseEntity<String> requests(@PathVariable String id,@RequestParam(required=false)String pathId,@RequestParam(required=false)Boolean success,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){pages(page,size);runs.progress(id);return ok(store.results(id,pathId,success,page,size));}
    @GetMapping("/runs/{id}/requests/{requestId}") public ResponseEntity<String> request(@PathVariable String id,@PathVariable String requestId){return ok(store.result(id,requestId));}
    @GetMapping("/runs/{id}/report") public ResponseEntity<String> report(@PathVariable String id){var run=runs.progress(id);if(!terminal(run))throw new CallerStore.Conflict("Report available after execution ends");String html=HtmlReport.render(run,store.results(id,null,false,0,100));return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"report-"+safeId(id)+".html\"").body(html);}
    @GetMapping("/runs/{id}/artifacts/{artifactId}") public ResponseEntity<InputStreamResource> artifact(@PathVariable String id,@PathVariable String artifactId)throws Exception{
        var a=store.artifact(id,artifactId);Path file=(Path)a.get("path");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(data.resolve("bodies").toRealPath()))throw new CallerStore.Missing("Artifact expired or missing");
        var input=Files.newInputStream(file);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(((Number)a.get("size")).longValue()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+safeId(artifactId)+".bin\"").body(new InputStreamResource(input));
    }
    @GetMapping("/runs/{id}/logs") public ResponseEntity<String> logs(@PathVariable String id,@RequestParam(required=false)Integer workerIndex)throws Exception{
        runs.progress(id);Path directory=data.resolve("logs").resolve(safeId(id));if(!Files.isDirectory(directory))return ok(Map.of("files",List.of(),"tail",""));
        List<String> files;try(var stream=Files.walk(directory,2)){files=stream.filter(Files::isRegularFile).map(directory::relativize).map(Path::toString).sorted().limit(1000).toList();}
        if(workerIndex!=null&&(workerIndex<1||workerIndex>32))throw new IllegalArgumentException("workerIndex must be 1..32");String name=workerIndex==null?"control.log":String.format("worker-%04d.log",workerIndex);Path log=directory.resolve(name);String tail="";
        if(Files.isRegularFile(log)){try(var channel=java.nio.channels.FileChannel.open(log)){long size=channel.size();channel.position(Math.max(0,size-65536));var bytes=java.nio.ByteBuffer.allocate((int)Math.min(size,65536));channel.read(bytes);tail=new String(bytes.array(),0,bytes.position(),StandardCharsets.UTF_8);}}
        return ok(Map.of("files",files,"stream",name,"tail",tail));
    }
    @GetMapping(value="/runs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter events(@PathVariable String id){
        runs.progress(id);SseEmitter emitter=new SseEmitter(30*60*1000L);AtomicBoolean pending=new AtomicBoolean(),ended=new AtomicBoolean();AtomicReference<ScheduledFuture<?>> subscription=new AtomicReference<>();
        Runnable cleanup=()->{ended.set(true);var future=subscription.get();if(future!=null)future.cancel(false);};emitter.onCompletion(cleanup);emitter.onTimeout(cleanup);emitter.onError(e->cleanup.run());
        subscription.set(snapshots.scheduleAtFixedRate(()->{if(ended.get()||!pending.compareAndSet(false,true))return;try{sends.execute(()->{try{var r=runs.progress(id);emitter.send(SseEmitter.event().name("progress").data(store.encode(r)));if(terminal(r)){cleanup.run();emitter.complete();}}catch(Exception e){cleanup.run();emitter.completeWithError(e);}finally{pending.set(false);}});}catch(RejectedExecutionException e){cleanup.run();emitter.complete();}},0,1,TimeUnit.SECONDS));return emitter;
    }
    static boolean terminal(Map<String,Object> r){return Set.of("COMPLETED","FAILED","CANCELLED","INTERRUPTED").contains(String.valueOf(r.get("status")));}
    private static String safeId(String value){if(value==null||!value.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("Invalid identifier");return value;}
    private static void pages(int page,int size){if(page<0||page>1000000||size<1||size>100)throw new IllegalArgumentException("page >=0, size 1..100 required");}
    @ExceptionHandler(Exception.class) public ResponseEntity<String> errors(Exception error){int status=error instanceof CallerStore.Missing?404:error instanceof CallerStore.Conflict?409:error instanceof RunService.Capacity?429:error instanceof IllegalArgumentException||error instanceof java.time.format.DateTimeParseException?400:500;return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(store.encode(Map.of("status",status,"title",HttpStatus.valueOf(status).getReasonPhrase(),"detail",status==500?"Internal failure; inspect application logs":String.valueOf(error.getMessage()))));}
    @PreDestroy public void close(){snapshots.shutdownNow();sends.shutdownNow();}
}
