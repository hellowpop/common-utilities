package com.inzent.apim.utilities.hazelcastmonitoring.collection;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.cluster.Member;
import com.hazelcast.collection.*;
import com.hazelcast.core.*;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;
import com.hazelcast.replicatedmap.ReplicatedMap;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.topic.ITopic;
import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** A read-only client observer, never a member and never an entry scanner. */
public final class ClusterCollector {
    private final java.util.function.Function<ClientConfig,HazelcastInstance> clientFactory;
    public ClusterCollector() { this(HazelcastClient::newHazelcastClient); }
    ClusterCollector(java.util.function.Function<ClientConfig,HazelcastInstance> clientFactory) { this.clientFactory=Objects.requireNonNull(clientFactory); }
    public Snapshot collect(Options options) {
        Instant started=now(); UUID id=UUID.randomUUID();
        long deadline=System.nanoTime()+options.collectionTimeout().toNanos();
        HazelcastInstance client=null;
        Snapshot result=null; Throwable primary=null;
        try {
            ClientConfig c=clientConfig(options);
            var readiness=new ClientReadiness();
            c.getConnectionStrategyConfig().setAsyncStart(true);
            c.addListenerConfig(new com.hazelcast.config.ListenerConfig(readiness.lifecycleListener()));
            c.addListenerConfig(new com.hazelcast.config.ListenerConfig(readiness.membershipListener()));
            long connectionDeadline=Math.min(deadline,System.nanoTime()+options.connectTimeout().toNanos());
            client=clientFactory.apply(c);
            readiness.await(connectionDeadline);
            result=collect(client,options,id,started,c.getClusterName(),deadline);
        } catch (InterruptedException e) { primary=e; Thread.currentThread().interrupt(); result=failed(id,started,options,e); }
        catch (Exception e) { primary=e; result=failed(id,started,options,e); }
        catch (Error e) { primary=e; throw e; }
        finally {
            Throwable cleanup=client==null?null:shutdown(client);
            if(cleanup instanceof Error fatal) {
                if(primary instanceof Error) { if(primary!=fatal)primary.addSuppressed(fatal); }
                else { if(primary!=null&&primary!=fatal)fatal.addSuppressed(primary); throw fatal; }
            } else if(cleanup!=null) {
                if(primary!=null&&primary!=cleanup)primary.addSuppressed(cleanup);
                if(result!=null)result=cleanupFailed(result);
            }
        }
        return result;
    }
    ClientConfig clientConfig(Options o) throws IOException {
        ClientConfig c=new ClientConfig(Objects.requireNonNull(o.clientSettings(),"Client settings are only loaded in collection mode"));
        c.setProperty("hazelcast.client.statistics.enabled","false");
        c.setProperty("hazelcast.logging.type","slf4j");
        // A monitoring client must not redirect itself to a different logical cluster.
        c.setInstanceName("hzmon-"+UUID.randomUUID());
        return c;
    }
    public Snapshot collect(HazelcastInstance client, Options options) {
        return collect(client,options,UUID.randomUUID(),now(),options.clusterName(),System.nanoTime()+options.collectionTimeout().toNanos());
    }
    private Snapshot collect(HazelcastInstance client, Options o, UUID id, Instant started, String clusterName, long deadline) {
        ExecutorService worker=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"hzmon-observer"); t.setDaemon(true); return t; });
        try {
            List<Member> members=call(worker,deadline,o,() -> List.copyOf(client.getCluster().getMembers()));
            List<DistributedObject> objects=call(worker,deadline,o,() -> List.copyOf(client.getDistributedObjects()));
            Instant inventoryTime=now();
            List<Snapshot.Node> nodes=members.stream().map(m -> {
                String address=m.getAddress().toString(); String name=m.getAttribute(o.nodeNameAttribute());
                return new Snapshot.Node(m.getUuid(),name==null||name.isBlank()?address:name,address,"OBSERVED_ACTIVE",m.isLiteMember());
            }).sorted(Comparator.comparing(n -> n.memberUuid().toString())).toList();
            List<Snapshot.ObjectInfo> counts=new ArrayList<>(); boolean partial=false, timedOut=false;
            for(var object:objects.stream().filter(value->!(value instanceof ITopic<?>))
                    .sorted(Comparator.comparing(DistributedObject::getServiceName).thenComparing(DistributedObject::getName)).toList()) {
                String type=type(object);
                if(type.equals("UNKNOWN")) {
                    counts.add(new Snapshot.ObjectInfo(type,object.getServiceName(),object.getName(),null,Snapshot.CountStatus.UNSUPPORTED,now(),null,null)); continue;
                }
                if(timedOut||System.nanoTime()>=deadline||Thread.currentThread().isInterrupted()) {
                    partial=true;
                    counts.add(new Snapshot.ObjectInfo(type,object.getServiceName(),object.getName(),null,Snapshot.CountStatus.NOT_COLLECTED,null,"DEADLINE","Collection deadline or interruption")); continue;
                }
                try {
                    long count=call(worker,deadline,o,() -> size(object));
                    counts.add(new Snapshot.ObjectInfo(type,object.getServiceName(),object.getName(),count,Snapshot.CountStatus.SUCCESS,now(),null,null));
                } catch(Exception ex) {
                    partial=true;
                    timedOut=ex instanceof TimeoutException || ex instanceof InterruptedException;
                    counts.add(new Snapshot.ObjectInfo(type,object.getServiceName(),object.getName(),null,Snapshot.CountStatus.ERROR,now(),code(ex),"Count unavailable"));
                }
            }
            boolean topologyChanged=false;
            if(!timedOut&&System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()) {
                try { topologyChanged=!ids(members).equals(ids(call(worker,deadline,o,() -> List.copyOf(client.getCluster().getMembers())))); }
                catch(Exception ex) { partial=true; }
            } else partial=true;
            return new Snapshot(id,o.clusterId(),clusterName,started,now(),members.size(),
                partial||topologyChanged?Snapshot.Status.PARTIAL:Snapshot.Status.SUCCESS,"UNKNOWN",topologyChanged,inventoryTime,true,null,null,nodes,counts);
        } catch(Exception ex) { return failed(id,started,o,ex); }
        finally { stopWorker(worker); }
    }
    private static Throwable shutdown(HazelcastInstance client) {
        try { client.shutdown(); return null; }
        catch(RuntimeException|Error failure) {
            try { client.getLifecycleService().terminate(); }
            catch(RuntimeException|Error cleanup) {
                if(cleanup!=failure) {
                    if(cleanup instanceof Error&&!(failure instanceof Error)) { cleanup.addSuppressed(failure); return cleanup; }
                    failure.addSuppressed(cleanup);
                }
            }
            return failure;
        }
    }
    private static Snapshot cleanupFailed(Snapshot s) {
        return new Snapshot(s.id(),s.clusterId(),s.clusterName(),s.executedAt(),s.completedAt(),s.activeNodeCount(),
            s.collectionStatus()==Snapshot.Status.FAILED?Snapshot.Status.FAILED:Snapshot.Status.PARTIAL,s.clusterState(),s.topologyChanged(),
            s.objectInventoryObservedAt(),s.objectInventoryComplete(),s.errorCode()==null?"CLIENT_SHUTDOWN_FAILED":s.errorCode(),
            (s.errorMessage()==null?"":s.errorMessage()+"; ")+"Client shutdown failed; forced termination attempted",s.nodes(),s.objects());
    }
    private static void stopWorker(ExecutorService worker) {
        boolean interrupted=Thread.interrupted();
        try {
            worker.shutdownNow();
            try {
                if(!worker.awaitTermination(250,TimeUnit.MILLISECONDS))System.err.println("Monitoring worker did not terminate after cancellation; operation/client may still be active");
            } catch(InterruptedException ex) { interrupted=true; }
        } finally { if(interrupted)Thread.currentThread().interrupt(); }
    }
    private static <T> T call(ExecutorService worker,long deadline,Options o,Callable<T> task) throws Exception {
        long remaining=Math.min(deadline-System.nanoTime(),o.operationTimeout().toNanos());
        if(remaining<=0)throw new TimeoutException();
        Future<T> f=worker.submit(task);
        try { return f.get(remaining,TimeUnit.NANOSECONDS); }
        catch(InterruptedException e) { f.cancel(true); Thread.currentThread().interrupt(); throw e; }
        catch(TimeoutException e) { f.cancel(true); throw e; }
        catch(ExecutionException e) {
            if(e.getCause() instanceof Error fatal)throw fatal;
            if(e.getCause() instanceof Exception cause)throw cause;
            throw e;
        }
    }
    private static Set<UUID> ids(List<Member> members) { Set<UUID> ids=new HashSet<>(); members.forEach(m -> ids.add(m.getUuid())); return ids; }
    private static long size(DistributedObject o) {
        if(o instanceof IMap<?,?> v)return v.size();
        if(o instanceof MultiMap<?,?> v)return v.size();
        if(o instanceof IList<?> v)return v.size();
        if(o instanceof ISet<?> v)return v.size();
        if(o instanceof IQueue<?> v)return v.size();
        if(o instanceof Ringbuffer<?> v)return v.size();
        if(o instanceof ReplicatedMap<?,?> v)return v.size();
        throw new IllegalArgumentException("Unsupported object");
    }
    private static String type(DistributedObject o) {
        if(o instanceof IMap)return "MAP"; if(o instanceof MultiMap)return "MULTIMAP";
        if(o instanceof IList)return "LIST"; if(o instanceof ISet)return "SET"; if(o instanceof IQueue)return "QUEUE";
        if(o instanceof Ringbuffer)return "RINGBUFFER"; if(o instanceof ReplicatedMap)return "REPLICATED_MAP";
        return "UNKNOWN";
    }
    private static String code(Exception e) { return e instanceof TimeoutException?"TIMEOUT":e.getClass().getSimpleName(); }
    private static Snapshot failed(UUID id,Instant started,Options o,Exception e) {
        return new Snapshot(id,o.clusterId(),o.clusterName(),started,now(),null,Snapshot.Status.FAILED,"UNKNOWN",false,null,false,
            code(e),"Cluster connection or inventory unavailable",List.of(),List.of());
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
