package com.inzent.apim.utilities.hazelcastmonitoring.export;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.persistence.SnapshotStore;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import com.inzent.apim.utilities.hazelcastmonitoring.time.DateTimes;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlExporter {
    public Path path(Snapshot s,Path directory) {
        var t=s.executedAt().atZone(DateTimes.STORAGE_ZONE);
        return directory.resolve(DateTimeFormatter.ofPattern("uuuu/MM/dd").format(t))
            .resolve(DateTimes.fileName(s.executedAt(),DateTimes.STORAGE_ZONE)+"_"+s.id()+".yml").toAbsolutePath().normalize();
    }
    private Yaml reader() {
        LoaderOptions o=new LoaderOptions(); o.setAllowDuplicateKeys(false); o.setMaxAliasesForCollections(0);
        o.setNestingDepthLimit(30); o.setCodePointLimit(128*1024*1024);
        return new Yaml(new SafeConstructor(o));
    }
    public void export(Snapshot snapshot,Path file) throws IOException {
        DumperOptions opts=new DumperOptions(); opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); opts.setPrettyFlow(true);
        String text=new Yaml(opts).dump(document(snapshot,false));
        try { AtomicFiles.write(file,w -> w.write(text)); }
        catch(FileAlreadyExistsException e) {
            try(var input=Files.newBufferedReader(file)) {
                Object existing=reader().load(input), expected=reader().load(text);
                Object legacy=reader().load(new Yaml(opts).dump(document(snapshot,true)));
                if(!Objects.equals(existing,expected)&&!Objects.equals(existing,legacy))throw new IOException("Conflicting snapshot file: "+file);
            } catch(org.yaml.snakeyaml.error.YAMLException parse) { throw new IOException("Invalid existing snapshot YAML",parse); }
        }
    }
    public int recover(SnapshotStore store,Options options) {
        UUID cursor=null; int failures=0;
        while(true) {
            var batch=store.pending(options.clusterId(),cursor);
            if(batch.isEmpty())break;
            for(var pending:batch) {
                try { export(store.load(pending.id()),pending.path()); store.markExport(pending.id(),"EXPORTED"); }
                catch(IOException ex) { store.markExport(pending.id(),"ERROR"); failures++; System.err.println("YAML recovery failed for run "+pending.id()); }
                cursor=pending.id();
            }
        }
        return failures;
    }
    private Map<String,Object> document(Snapshot s,boolean legacy) {
        Map<String,Object> value=map("id",s.id().toString(),"clusterId",s.clusterId(),"clusterName",s.clusterName(),
            "completedAt",time(s.completedAt(),legacy),"activeNodeCount",s.activeNodeCount(),"collectionStatus",s.collectionStatus().name(),
            "clusterState",s.clusterState(),"topologyChanged",s.topologyChanged(),"objectInventoryComplete",s.objectInventoryComplete(),
            "objectInventoryObservedAt",time(s.objectInventoryObservedAt(),legacy),"errorCode",s.errorCode(),"errorMessage",s.errorMessage());
        value.put("nodes",s.nodes().stream().sorted(Comparator.comparing(n->n.memberUuid().toString())).map(n -> map("memberUuid",n.memberUuid().toString(),"nodeName",n.nodeName(),"nodeAddress",n.nodeAddress(),"nodeStatus",n.nodeStatus(),"liteMember",n.liteMember())).toList());
        value.put("objects",s.objects().stream().sorted(Comparator.comparing(Snapshot.ObjectInfo::serviceName).thenComparing(Snapshot.ObjectInfo::objectName)).map(o -> map("objectType",o.objectType(),"serviceName",o.serviceName(),"objectName",o.objectName(),
            "objectCount",o.objectCount(),"countStatus",o.countStatus().name(),"observedAt",time(o.observedAt(),legacy),"errorCode",o.errorCode(),"errorMessage",o.errorMessage())).toList());
        return legacy?map("schemaVersion",1,"snapshots",map(s.executedAt().toString(),value)):
            map("schemaVersion",2,"timeZone",DateTimes.STORAGE_ZONE.getId(),"snapshots",map(DateTimes.format(s.executedAt()),value));
    }
    private static String time(Instant value,boolean legacy) { return value==null?null:legacy?value.toString():DateTimes.format(value); }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]); return result;
    }
}
