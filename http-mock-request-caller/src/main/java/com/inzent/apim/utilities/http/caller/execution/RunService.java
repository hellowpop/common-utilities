package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.config.CallerProperties;
import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import com.inzent.apim.utilities.http.caller.scenario.*;
import com.inzent.apim.utilities.http.caller.transport.NettyTransport;
import com.inzent.apim.utilities.http.caller.script.ScriptService;
import com.inzent.apim.utilities.http.caller.logging.RunLogManager;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public final class RunService implements AutoCloseable {
    final CallerStore store;final CallerProperties properties;final Path data;
    final ScriptService scripts=new ScriptService();final NettyTransport transport;
    final StorageMaintenance storage;
    private final ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor(named("caller-storage"));
    final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2,named("caller-timer"));
    private final ExecutorService controls,workers;
    private final Semaphore admission,workerSlots;
    private final ConcurrentMap<String,RunState> active=new ConcurrentHashMap<>();
    private final Map<String,String> activeScenario=new HashMap<>();
    private volatile boolean closed,degraded;
    public RunService(CallerStore store,CallerProperties p){
        this.store=store;properties=p;data=Path.of(p.dataDirectory()).toAbsolutePath().normalize();
        try{Files.createDirectories(data.resolve("temp"));}catch(Exception e){throw new IllegalStateException(e);}
        ResultWriter.recover(store,data);store.recoverInterrupted();
        storage=new StorageMaintenance(store,data,p.retentionDays());storage.sweep(Set.of(),true);
        transport=new NettyTransport(data.resolve("temp"),p.maxResponseBytes());controls=Executors.newFixedThreadPool(p.maxConcurrentRuns(),named("caller-control"));workers=Executors.newFixedThreadPool(p.maxTotalWorkers(),named("caller-worker"));
        admission=new Semaphore(p.maxConcurrentRuns());workerSlots=new Semaphore(p.maxTotalWorkers());
        maintenance.scheduleWithFixedDelay(()->{try{storage.sweep(active.keySet(),false);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(RunService.class).error("Storage retention retry pending",e);}},1,1,TimeUnit.HOURS);
    }
    public Scenario validate(Scenario s){new ScenarioValidator().validate(s);scripts.validate(s.scenarioHooks.pre);scripts.validate(s.scenarioHooks.post);scripts.validate(s.workerHooks.pre);scripts.validate(s.workerHooks.post);for(var p:s.paths){scripts.validate(p.pathHooks.pre);scripts.validate(p.pathHooks.post);scripts.validate(p.requestHooks.pre);scripts.validate(p.requestHooks.post);}return s;}
    public synchronized Map<String,Object> start(String scenarioId,String key){
        if(closed||degraded||scripts.isDegraded())throw new IllegalStateException("Execution service unavailable");
        if(key!=null){if(key.isBlank()||key.length()>200)throw new IllegalArgumentException("Invalid Idempotency-Key");var old=store.byIdempotencyKey(scenarioId,key);if(old!=null)return old;}
        if(activeScenario.containsKey(scenarioId))throw new CallerStore.Conflict("Scenario already running");
        Scenario s=store.copy(store.scenario(scenarioId));validate(s);if(!s.enabled)throw new IllegalArgumentException("Scenario disabled");
        long count=new ScenarioValidator().validate(s);
        if(!admission.tryAcquire())throw new Capacity("Concurrent run limit reached");
        if(!workerSlots.tryAcquire(s.maxWorkers)){admission.release();throw new Capacity("Global worker limit reached");}
        RunState state=new RunState(s,count);state.idempotencyKey=key;
        try{var snapshot=state.snapshot();if(key!=null)snapshot.put("idempotencyKey",key);store.saveRun(snapshot);active.put(state.id,state);activeScenario.put(scenarioId,state.id);controls.submit(()->execute(state,key));return snapshot;}
        catch(RuntimeException e){active.remove(state.id);activeScenario.remove(scenarioId);workerSlots.release(s.maxWorkers);admission.release();throw e;}
    }
    public Map<String,Object> progress(String id){RunState s=active.get(id);return s==null?store.run(id):s.snapshot();}
    public Map<String,Object> cancel(String id){RunState s=active.get(id);if(s!=null)synchronized(s){if(s.endedAt==null){s.stop("USER");s.status("STOPPING");}}return progress(id);}
    public synchronized boolean isRunningScenario(String id){return activeScenario.containsKey(id);}
    public boolean degraded(){return degraded||scripts.isDegraded();}
    private void execute(RunState state,String key){
        ResultWriter writer=new ResultWriter(store,state,data);RunLogManager logs=null;
        var timeout=timer.schedule(()->state.stop("TIMEOUT"),state.scenario.runTimeoutMillis,TimeUnit.MILLISECONDS);
        try{
            logs=RunLogManager.open(data.resolve("logs").resolve(state.id),state.scenario.logging.enabled,state.scenario.logging.maxFileSize,state.scenario.logging.maxFiles,state.scenario.logging.gzip);
            logs.log("control","START run="+state.id+" seed="+state.seed);state.status("STARTING");
            Map<String,Object> context=context(state,null,null,null,"scenario","pre");context.put("vars",state.initialVars);
            boolean pre=hook(state,state.scenario.scenarioHooks.pre,context,"scenario","pre",logs,"control");
            if(!pre)state.fatal(new IllegalStateException("Scenario pre hook failed"));
            if(pre&&!state.stopping()){
                state.status("RUNNING");CountDownLatch latch=new CountDownLatch(state.scenario.maxWorkers);long t0=System.nanoTime();
                List<ScheduledFuture<?>> scheduled=new ArrayList<>();
                for(int i=0;i<state.scenario.maxWorkers;i++){
                    RunState.Worker w=new RunState.Worker(i+1,latch);w.vars.putAll(store.decode(store.encode(state.initialVars)));state.workers.add(w);
                    long delay=state.scenario.maxWorkers==1?0:(long)(state.scenario.rampUpSeconds*1e9*i/(state.scenario.maxWorkers-1));
                    RunLogManager ownedLogs=logs;
                    scheduled.add(timer.schedule(()->{if(state.stopping()){w.cancel();return;}try{workers.submit(()->new WorkerRunner(this,state,w,writer,ownedLogs).run());}catch(RuntimeException e){state.fatal(e);w.cancel();}},Math.max(0,t0+delay-System.nanoTime()),TimeUnit.NANOSECONDS));
                }
                while(!latch.await(250,TimeUnit.MILLISECONDS)){
                    if(state.stopping())for(var w:state.workers)w.cancel();
                    checkpoint(state,key);
                }
                scheduled.forEach(f->f.cancel(false));
            }
            state.status("FINALIZING");
            Thread.interrupted();context=context(state,null,null,null,"scenario","post");context.put("vars",state.initialVars);context.put("workerVars",state.workers.stream().map(w->new LinkedHashMap<>(w.vars)).toList());
            if(!hook(state,state.scenario.scenarioHooks.post,context,"scenario","post",logs,"control"))state.fatal(new IllegalStateException("Scenario post hook failed"));
        }catch(Throwable e){state.fatal(e);state.stop("FATAL");}
        finally{
            timeout.cancel(false);state.stop.compareAndSet(null,"FINISHED");
            for(var w:state.workers)w.cancel();
            // Never return admission while a worker still owns network/body/context resources.
            for(var w:state.workers)while(w.thread!=null){try{Thread.sleep(10);}catch(InterruptedException e){Thread.interrupted();}}
            writer.close();Thread.interrupted();
            if(logs!=null)try{logs.log("control","END completed="+state.completed+" failed="+state.failed);logs.close();}catch(Exception e){state.fatal(e);}
            synchronized(state){state.skipped=Math.max(0,state.planned-state.completed);state.endedAt=Instant.now().toString();String reason=state.stop.get();if("USER".equals(reason)||"SHUTDOWN".equals(reason))state.status("CANCELLED");else if(state.error!=null||"TIMEOUT".equals(reason))state.status("FAILED");else{state.stop.compareAndSet("FINISHED",null);state.status("COMPLETED");}}
            writer.saveTerminal();
            if(state.writerFailed||!"OK".equals(state.persistenceState))degraded=true;
            synchronized(this){activeScenario.remove(state.scenario.id);active.remove(state.id);workerSlots.release(state.scenario.maxWorkers);admission.release();}
        }
    }
    private void checkpoint(RunState s,String key){try{var value=s.snapshot();if(key!=null)value.put("idempotencyKey",key);store.saveRun(value);}catch(Exception e){s.fatal(e);}}
    boolean hook(RunState s,String script,Map<String,Object> context,String scope,String phase,RunLogManager logs,String stream){
        if(script==null||script.isBlank())return true;
        try{scripts.execute(script,context,Duration.ofMillis(properties.scriptTimeoutMillis()));s.hook(scope,phase,"OK",null);logs.log(stream,"HOOK "+scope+"."+phase+" OK");return true;}
        catch(Exception e){s.hook(scope,phase,"FAILED",RunState.brief(e));logs.log(stream,"HOOK "+scope+"."+phase+" FAILED "+e.getClass().getSimpleName());return false;}
    }
    static Map<String,Object> context(RunState s,RunState.Worker w,Scenario.RequestPath p,Map<String,Object> exchange,String scope,String phase){
        Map<String,Object> c=new LinkedHashMap<>();c.put("scenario",Collections.unmodifiableMap(Map.of("id",s.scenario.id,"title",s.scenario.title,"endpointBaseUrl",s.scenario.endpointBaseUrl)));
        c.put("run",Collections.unmodifiableMap(Map.of("id",s.id,"seed",s.seed,"cancelled",s.stopping())));
        c.put("scope",new LinkedHashMap<>(Map.of("level",scope,"phase",phase,"failed",s.error!=null,"cancelled",s.stopping())));
        if(w!=null){Map<String,Object> worker=new LinkedHashMap<>();worker.put("index",w.index);worker.put("threadName",Thread.currentThread().getName());worker.put("threadId",Thread.currentThread().getId());worker.put("vars",w.vars);c.put("worker",worker);}
        if(p!=null)c.put("path",Collections.unmodifiableMap(Map.of("id",p.id,"name",p.name,"requestPath",p.requestPath)));
        if(exchange!=null)c.put("exchange",exchange);return c;
    }
    private static java.util.concurrent.ThreadFactory named(String prefix){var n=new java.util.concurrent.atomic.AtomicInteger();return r->{Thread t=new Thread(r,prefix+"-"+n.incrementAndGet());t.setDaemon(true);return t;};}
    @Override @PreDestroy public void close(){closed=true;maintenance.shutdownNow();active.values().forEach(s->s.stop("SHUTDOWN"));controls.shutdown();workers.shutdown();boolean interrupted=false;try{if(!controls.awaitTermination(30,TimeUnit.SECONDS)){degraded=true;controls.shutdownNow();}workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}timer.shutdownNow();transport.close();scripts.close();if(interrupted)Thread.currentThread().interrupt();}
    public static final class Capacity extends RuntimeException{public Capacity(String m){super(m);}}
}
