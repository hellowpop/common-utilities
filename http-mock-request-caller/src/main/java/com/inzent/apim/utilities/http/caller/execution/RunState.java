package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class RunState {
    final String id=UUID.randomUUID().toString();
    final Scenario scenario;
    final long seed,planned,beginNanos=System.nanoTime();
    final String startedAt=Instant.now().toString();
    final List<Worker> workers=new CopyOnWriteArrayList<>();
    final List<Map<String,Object>> hooks=new ArrayList<>();
    final Map<String,Object> initialVars=new LinkedHashMap<>();
    final List<Map<String,Object>> pathIterations=new ArrayList<>();
    final Map<String,Long> statuses=new TreeMap<>(),errors=new TreeMap<>();
    final Map<String,long[]> pathStats=new LinkedHashMap<>();
    final long[] histogram=new long[64];
    final AtomicReference<String> stop=new AtomicReference<>();
    String status="QUEUED",error,endedAt,idempotencyKey;
    volatile String persistenceState="OK";
    long started,completed,succeeded,failed,skipped,receivedBytes,sentBytes,hookFailures,wireSamples,wireNanos,minNanos=Long.MAX_VALUE,maxNanos,sequence;
    long retainedBytes;
    volatile boolean writerFailed;
    RunState(Scenario s,long count){scenario=s;planned=count;seed=s.seed==null?ThreadLocalRandom.current().nextLong():s.seed;}
    boolean stopping(){return stop.get()!=null;}
    void stop(String reason){stop.compareAndSet(null,reason);for(Worker w:workers)w.cancel();}
    synchronized void fatal(Throwable failure){if(error==null)error=brief(failure);stop("FATAL");}
    synchronized void status(String value){status=value;sequence++;}
    synchronized void requestStarted(){started++;sequence++;}
    synchronized void recoveryPending(){if(!"LOST_RESULTS".equals(persistenceState))persistenceState="RECOVERY_PENDING";}
    synchronized void lostResults(){persistenceState="LOST_RESULTS";}
    synchronized void result(Map<String,Object> r){
        completed++;boolean ok=Boolean.TRUE.equals(r.get("success"));if(ok)succeeded++;else failed++;
        Object code=r.get("status");if(code!=null)statuses.merge(code.toString(),1L,Long::sum);
        if(r.get("errorType")!=null)errors.merge(r.get("errorType").toString(),1L,Long::sum);
        long recv=number(r.get("receivedBytes")),sent=number(r.get("sentBytes"));receivedBytes+=recv;sentBytes+=sent;
        long[] stats=pathStats.computeIfAbsent(String.valueOf(r.get("pathId")),x->new long[3]);stats[0]++;stats[ok?1:2]++;
        if(r.get("wireNanos")!=null){long n=number(r.get("wireNanos"));wireSamples++;wireNanos+=n;minNanos=Math.min(minNanos,n);maxNanos=Math.max(maxNanos,n);int bucket=n<=0?0:Math.min(63,64-Long.numberOfLeadingZeros(n));histogram[bucket]++;}
        sequence++;
    }
    synchronized void hook(String scope,String phase,String outcome,String message){
        if(hooks.size()<1000){Map<String,Object> h=new LinkedHashMap<>();h.put("scope",scope);h.put("phase",phase);h.put("outcome",outcome);h.put("error",message);h.put("at",Instant.now().toString());hooks.add(h);}
        if("FAILED".equals(outcome))hookFailures++;sequence++;
    }
    synchronized void iteration(Map<String,Object> value){if(pathIterations.size()<1000)pathIterations.add(new LinkedHashMap<>(value));}
    synchronized boolean reserveBody(long size,long limit){if(size>limit-retainedBytes)return false;retainedBytes+=size;return true;}
    synchronized Map<String,Object> snapshot(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("scenarioId",scenario.id);m.put("scenarioVersion",scenario.version);m.put("title",scenario.title);m.put("snapshot",scenario);m.put("seed",seed);
        m.put("status",status);m.put("outcome",failed>0||hookFailures>0||error!=null||stopping()?"WITH_FAILURES":"SUCCESS");m.put("startedAt",startedAt);m.put("endedAt",endedAt);m.put("error",error);m.put("stopReason",stop.get());m.put("persistenceState",persistenceState);m.put("statisticsComplete",!"LOST_RESULTS".equals(persistenceState));
        if(idempotencyKey!=null)m.put("idempotencyKey",idempotencyKey);
        m.put("planned",planned);m.put("started",started);m.put("completed",completed);m.put("succeeded",succeeded);m.put("failed",failed);m.put("skipped",skipped);m.put("inFlight",started-completed);m.put("remaining",Math.max(0,planned-completed-skipped));m.put("progress",planned==0?1.0:(double)completed/planned);m.put("resolvedProgress",planned==0?1.0:(double)(completed+skipped)/planned);
        m.put("sentBytes",sentBytes);m.put("receivedBytes",receivedBytes);m.put("statusCounts",new TreeMap<>(statuses));m.put("errorCounts",new TreeMap<>(errors));
        List<Map<String,Object>> ps=new ArrayList<>();pathStats.forEach((id,v)->ps.add(Map.of("pathId",id,"completed",v[0],"succeeded",v[1],"failed",v[2])));m.put("pathStatistics",ps);
        m.put("hooks",new ArrayList<>(hooks));m.put("hookFailures",hookFailures);m.put("hookDetailsTruncated",hooks.size()>=1000);m.put("pathIterations",new ArrayList<>(pathIterations));m.put("pathDetailsTruncated",pathIterations.size()>=1000);
        m.put("workers",workers.stream().map(Worker::snapshot).toList());m.put("activeWorkers",workers.stream().filter(w->w.thread!=null).count());
        long elapsed=endedAt==null?System.nanoTime()-beginNanos:Math.max(0,java.time.Duration.between(Instant.parse(startedAt),Instant.parse(endedAt)).toNanos());m.put("elapsedMillis",elapsed/1e6);m.put("throughput",elapsed<=0?0:completed*1e9/elapsed);
        m.put("latency",Map.of("samples",wireSamples,"minMillis",wireSamples==0?0:minNanos/1e6,"maxMillis",maxNanos/1e6,"meanMillis",wireSamples==0?0:(double)wireNanos/wireSamples/1e6,"p50Millis",percentile(.5),"p95Millis",percentile(.95),"p99Millis",percentile(.99),"approximate",true));m.put("sequence",sequence);m.put("asOf",Instant.now().toString());return m;
    }
    private double percentile(double p){if(wireSamples==0)return 0;long target=(long)Math.ceil(wireSamples*p),n=0;for(int i=0;i<histogram.length;i++){n+=histogram[i];if(n>=target)return Math.scalb(1.0,i)/1e6;}return maxNanos/1e6;}
    static long number(Object v){return v instanceof Number n?n.longValue():0;}
    static String brief(Throwable e){String msg=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());return msg.length()>4096?msg.substring(0,4096):msg;}
    static final class Worker {
        final int index;final CountDownLatch done;final AtomicBoolean claimed=new AtomicBoolean();
        final Map<String,Object> vars=new LinkedHashMap<>();
        volatile Thread thread;volatile String status="SCHEDULED",startedAt,endedAt;volatile long completed;
        Worker(int i,CountDownLatch latch){index=i;done=latch;}
        void cancel(){if(claimed.compareAndSet(false,true)){status="SKIPPED";done.countDown();}Thread t=thread;if(t!=null)t.interrupt();}
        Map<String,Object> snapshot(){Map<String,Object> m=new LinkedHashMap<>();m.put("index",index);m.put("status",status);m.put("threadName",thread==null?null:thread.getName());m.put("startedAt",startedAt);m.put("endedAt",endedAt);m.put("completed",completed);return m;}
    }
}
