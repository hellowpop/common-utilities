package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Bounded producer queue; storage failure switches to replayable checksummed local journal. */
final class ResultWriter implements AutoCloseable {
    private final CallerStore store;private final RunState state;private final Path journal;
    private final BlockingQueue<Map<String,Object>> queue=new ArrayBlockingQueue<>(1024);
    private final Thread thread;private volatile boolean ending;
    ResultWriter(CallerStore store,RunState state,Path data){this.store=store;this.state=state;journal=data.resolve("journal").resolve(state.id+".jsonl");thread=new Thread(this::drain,"caller-writer-"+state.id);thread.setDaemon(true);thread.start();}
    void offer(Map<String,Object> result)throws Exception{
        if(!queue.offer(result,5,TimeUnit.SECONDS))throw new IllegalStateException("Result queue timeout");
    }
    private void drain(){
        while(!ending||!queue.isEmpty())try{
            var r=queue.poll(100,TimeUnit.MILLISECONDS);if(r==null)continue;
            try{if(state.writerFailed)throw new IllegalStateException("journal mode");persist(store,r);}
            catch(Exception e){state.writerFailed=true;state.fatal(e);state.recoveryPending();append(Map.of("type","result","value",r));}
        }catch(InterruptedException e){/* close waits for all queued results */}
        catch(Exception e){state.lostResults();state.fatal(e);}
    }
    @SuppressWarnings("unchecked")
    static void persist(CallerStore store,Map<String,Object> result){
        Object artifacts=result.get("artifacts");if(artifacts instanceof List<?> list)for(Object item:list){var a=(Map<String,Object>)item;store.artifact(String.valueOf(result.get("runId")),String.valueOf(a.get("id")),Path.of(String.valueOf(a.get("path"))),RunState.number(a.get("size")),String.valueOf(a.get("contentType")));}
        Map<String,Object> publicResult=new LinkedHashMap<>(result);publicResult.remove("artifacts");store.saveResult(publicResult);
    }
    synchronized void append(Map<String,Object> entry)throws Exception{
        Files.createDirectories(journal.getParent());String payload=store.encode(entry);String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        byte[] bytes=(store.encode(Map.of("schemaVersion",1,"payload",payload,"checksum",checksum))+"\n").getBytes(StandardCharsets.UTF_8);
        long existing=Files.exists(journal)?Files.size(journal):0;long global;try(var paths=Files.list(journal.getParent())){global=paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){return 0;}}).sum();}
        if(existing+bytes.length>64L*1024*1024||global+bytes.length>256L*1024*1024)throw new IllegalStateException("Journal budget exceeded");
        try(FileChannel channel=FileChannel.open(journal,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);}
    }
    void saveTerminal(){
        boolean journaled=false;
        if(state.writerFailed||!"OK".equals(state.persistenceState))try{append(Map.of("type","run","value",state.snapshot()));journaled=true;}catch(Exception e){state.lostResults();state.fatal(e);}
        try{store.saveRun(state.snapshot());}catch(Exception e){state.recoveryPending();if(!journaled)try{append(Map.of("type","run","value",state.snapshot()));}catch(Exception failure){state.lostResults();state.fatal(failure);}}
    }
    @Override public void close(){ending=true;boolean interrupted=false;while(thread.isAlive())try{thread.join(1000);}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    @SuppressWarnings("unchecked")
    static void recover(CallerStore store,Path data){
        Path directory=data.resolve("journal");if(!Files.isDirectory(directory))return;
        try(var files=Files.list(directory)){
            for(Path file:files.filter(p->p.getFileName().toString().endsWith(".jsonl")).toList()){
                if(Files.size(file)>64L*1024*1024)throw new IllegalStateException("Journal exceeds recovery budget");
                byte[] raw=Files.readAllBytes(file);int complete=raw.length;while(complete>0&&raw[complete-1]!='\n')complete--;
                // Frame bytes first: a crash may tear a UTF-8 code point as well as JSON syntax.
                try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.ByteArrayInputStream(raw,0,complete),StandardCharsets.UTF_8.newDecoder()))){
                    String line;while((line=reader.readLine())!=null){Map<String,Object> record=store.decode(line);
                        String payload=String.valueOf(record.get("payload"));String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));if(!checksum.equals(record.get("checksum")))throw new IllegalStateException("Journal checksum mismatch: "+file);
                        var entry=store.decode(payload);var value=(Map<String,Object>)entry.get("value");if("result".equals(entry.get("type")))persist(store,value);else if("run".equals(entry.get("type"))){var existing=store.run(String.valueOf(value.get("id")));if("LOST_RESULTS".equals(existing.get("persistenceState")))value.put("persistenceState","LOST_RESULTS");if(!"LOST_RESULTS".equals(value.get("persistenceState")))value.put("persistenceState","OK");else value.put("statisticsComplete",false);store.saveRun(value);}else throw new IllegalStateException("Unknown journal record type");
                    }
                }
                if(complete<raw.length){Path quarantine=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".incomplete");Files.write(quarantine,Arrays.copyOfRange(raw,complete,raw.length),StandardOpenOption.CREATE_NEW);String runId=file.getFileName().toString().replaceFirst("\\.jsonl$","");var run=store.run(runId);run.put("persistenceState","LOST_RESULTS");run.put("statisticsComplete",false);store.saveRun(run);}
                Files.delete(file);
            }
        }catch(Exception e){throw new IllegalStateException("Cannot recover execution journal; original journal retained",e);}
    }
}
