package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Deletes only tombstoned ended runs and startup orphans inside explicit managed directories. */
final class StorageMaintenance {
    private final CallerStore store;private final Path data;private final int days;
    private final AtomicLong retained=new AtomicLong();
    StorageMaintenance(CallerStore store,Path data,int days){this.store=store;this.data=data.toAbsolutePath().normalize();this.days=days;try{retained.set(size(this.data.resolve("bodies")));}catch(IOException e){throw new IllegalStateException(e);}}
    boolean reserve(long bytes){long current;do{current=retained.get();if(bytes>4L*1024*1024*1024-current)return false;}while(!retained.compareAndSet(current,current+bytes));return true;}
    void sweep(Set<String> active,boolean startup){
        try{
            Instant cutoff=Instant.now().minus(Duration.ofDays(days));
            for(String id:store.expiredRuns(cutoff)){
                Map<String,Object> run=store.run(id);Object ended=run.get("endedAt");if(ended!=null&&Instant.parse(ended.toString()).isBefore(cutoff)&&!active.contains(id))store.markCleanup(id);
            }
            for(String id:store.pendingCleanup()){
                if(active.contains(id))continue;if(!id.matches("[a-zA-Z0-9-]{1,64}"))throw new IOException("Invalid cleanup run identifier");
                remove(data.resolve("bodies").resolve(id));remove(data.resolve("logs").resolve(id));remove(data.resolve("reports").resolve(id));store.finishCleanup(id);
            }
            if(startup){
                remove(data.resolve("temp"));Files.createDirectories(data.resolve("temp"));
                Set<Path> referenced=store.artifactPaths();Path bodies=data.resolve("bodies");
                if(Files.isDirectory(bodies))try(var files=Files.walk(bodies)){for(Path p:files.filter(f->Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)).toList())if(!referenced.contains(p)){long bytes=Files.size(p);Files.delete(p);retained.updateAndGet(n->Math.max(0,n-bytes));}}
            }
        }catch(Exception e){throw new IllegalStateException("Managed storage cleanup failed; tombstones retained for retry",e);}
    }
    private long remove(Path target)throws IOException{
        Path normalized=target.toAbsolutePath().normalize();if(!normalized.startsWith(data)||normalized.equals(data))throw new IOException("Cleanup outside data directory");
        Path relative=data.relativize(normalized),cursor=data;for(Path component:relative){cursor=cursor.resolve(component);if(Files.isSymbolicLink(cursor))throw new IOException("Managed directory cannot be a symbolic link: "+cursor);}
        if(!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS))return 0;
        boolean accounted=normalized.startsWith(data.resolve("bodies"));long removed=0;
        try(var paths=Files.walk(normalized)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()){long bytes=Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)?Files.size(p):0;Files.delete(p);removed+=bytes;if(accounted&&bytes>0)retained.updateAndGet(n->Math.max(0,n-bytes));}}return removed;
    }
    private static long size(Path root)throws IOException{if(!Files.exists(root))return 0;try(var paths=Files.walk(root)){long total=0;for(Path p:paths.filter(f->Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)).toList())total=Math.addExact(total,Files.size(p));return total;}}
}
