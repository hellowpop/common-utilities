package com.inzent.apim.utilities.http.caller.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable, parameterized JDBC store. JSON snapshots avoid coupling execution threads to ORM sessions. */
public class CallerStore {
    private static final java.time.format.DateTimeFormatter FIXED_INSTANT=new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final TypeReference<Map<String,Object>> MAP=new TypeReference<>(){};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Path data;
    public CallerStore(JdbcTemplate jdbc,ObjectMapper json,Path data){this.jdbc=jdbc;this.json=json;this.data=data.toAbsolutePath().normalize();}
    public void initialize(){
        try{Files.createDirectories(data);SpringLiquibase migration=new SpringLiquibase();migration.setDataSource(jdbc.getDataSource());migration.setChangeLog("classpath:db/changelog/master.xml");migration.afterPropertiesSet();}
        catch(Exception e){throw new IllegalStateException("Database migration failed",e);}
    }
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON",e);}}
    public Map<String,Object> decode(String value){try{return json.readValue(value,MAP);}catch(Exception e){throw new IllegalStateException("Invalid stored JSON",e);}}
    public Scenario copy(Scenario value){return json.convertValue(value,Scenario.class);}
    public Scenario scenario(String id){
        var rows=jdbc.query("select definition from caller_scenario where id=? and deleted=false",(rs,n)->rs.getString(1),id);
        if(rows.isEmpty())throw new Missing("Scenario not found");
        try{return json.readValue(rows.get(0),Scenario.class);}catch(Exception e){throw new IllegalStateException(e);}
    }
    public synchronized Scenario createScenario(Scenario input){
        Scenario s=copy(input);s.id=UUID.randomUUID().toString();s.version=1;
        jdbc.update("insert into caller_scenario(id,version,title,definition,deleted) values(?,?,?,?,false)",s.id,s.version,s.title,encode(s));return s;
    }
    public synchronized Scenario updateScenario(String id,Scenario input){
        Scenario s=copy(input);long previous=s.version;s.id=id;s.version=previous+1;
        if(jdbc.update("update caller_scenario set version=?,title=?,definition=? where id=? and version=? and deleted=false",s.version,s.title,encode(s),id,previous)!=1)throw new Conflict("Scenario changed or no longer exists; reload before editing");return s;
    }
    public synchronized void deleteScenario(String id){if(jdbc.update("update caller_scenario set deleted=true where id=? and deleted=false",id)==0)throw new Missing("Scenario not found");}
    public List<Scenario> scenarios(String title,int page,int size){
        return jdbc.query("select definition from caller_scenario where deleted=false and lower(title) like ? order by title,id limit ? offset ?",(rs,n)->{try{return json.readValue(rs.getString(1),Scenario.class);}catch(Exception e){throw new IllegalStateException(e);}},like(title),size,(long)page*size);
    }
    public synchronized void saveRun(Map<String,Object> run){
        String id=String.valueOf(run.get("id")),value=encode(run);
        if(jdbc.update("update caller_run set status=?,document=?,idempotency_key=coalesce(?,idempotency_key) where id=?",run.get("status"),value,run.get("idempotencyKey"),id)==0)
            jdbc.update("insert into caller_run(id,scenario_id,title,started_at,status,document,idempotency_key) values(?,?,?,?,?,?,?)",id,run.get("scenarioId"),run.get("title"),canonical(run.get("startedAt").toString()),run.get("status"),value,run.get("idempotencyKey"));
    }
    public Map<String,Object> byIdempotencyKey(String scenarioId,String key){var rows=jdbc.query("select document from caller_run where scenario_id=? and idempotency_key=?",(rs,n)->decode(rs.getString(1)),scenarioId,key);return rows.isEmpty()?null:rows.get(0);}
    public Map<String,Object> run(String id){
        var rows=jdbc.query("select document from caller_run where id=?",(rs,n)->decode(rs.getString(1)),id);
        if(rows.isEmpty())throw new Missing("Run not found");return rows.get(0);
    }
    public List<Map<String,Object>> searchRuns(String scenarioId,String title,String from,String to,String status,int page,int size){
        StringBuilder sql=new StringBuilder("select document from caller_run where lower(title) like ?");List<Object> args=new ArrayList<>(List.of(like(title)));
        if(scenarioId!=null&&!scenarioId.isBlank()){sql.append(" and scenario_id=?");args.add(scenarioId);}
        if(from!=null&&!from.isBlank()){sql.append(" and started_at>=?");args.add(canonical(from));}
        if(to!=null&&!to.isBlank()){sql.append(" and started_at<?");args.add(canonical(to));}
        if(status!=null&&!status.isBlank()){sql.append(" and status=?");args.add(status);}
        sql.append(" order by started_at desc,id desc limit ? offset ?");args.add(size);args.add((long)page*size);
        return jdbc.query(sql.toString(),(rs,n)->decode(rs.getString(1)),args.toArray());
    }
    public synchronized void saveResult(Map<String,Object> result){
        try{jdbc.update("insert into caller_result(id,run_id,path_id,success,document) values(?,?,?,?,?)",result.get("id"),result.get("runId"),result.get("pathId"),result.get("success"),encode(result));}
        catch(DuplicateKeyException ignored){/* request id is a stable idempotency key */}
    }
    public Map<String,Object> result(String runId,String requestId){
        var rows=jdbc.query("select document from caller_result where run_id=? and id=?",(rs,n)->decode(rs.getString(1)),runId,requestId);
        if(rows.isEmpty())throw new Missing("Request not found");return rows.get(0);
    }
    public List<Map<String,Object>> results(String runId,String pathId,Boolean success,int page,int size){
        StringBuilder sql=new StringBuilder("select document from caller_result where run_id=?");List<Object> args=new ArrayList<>(List.of(runId));
        if(pathId!=null&&!pathId.isBlank()){sql.append(" and path_id=?");args.add(pathId);}
        if(success!=null){sql.append(" and success=?");args.add(success);}
        sql.append(" order by id limit ? offset ?");args.add(size);args.add((long)page*size);
        return jdbc.query(sql.toString(),(rs,n)->decode(rs.getString(1)),args.toArray());
    }
    public synchronized int recoverInterrupted(){
        var unfinished=jdbc.query("select document from caller_run where status not in ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')",(rs,n)->decode(rs.getString(1)));
        for(var run:unfinished){run.put("status","INTERRUPTED");run.put("outcome","WITH_FAILURES");run.put("endedAt",Instant.now().toString());run.put("error","Application stopped before execution cleanup completed");run.put("statisticsComplete",false);saveRun(run);}return unfinished.size();
    }
    public void artifact(String runId,String artifactId,Path path,long size,String contentType){
        Path normalized=path.toAbsolutePath().normalize();if(!normalized.startsWith(data.resolve("bodies")))throw new IllegalArgumentException("Artifact outside managed directory");
        try{jdbc.update("insert into caller_artifact(id,run_id,path,body_size,content_type) values(?,?,?,?,?)",artifactId,runId,data.relativize(normalized).toString(),size,contentType);}catch(DuplicateKeyException ignored){}
    }
    public Map<String,Object> artifact(String runId,String id){
        var rows=jdbc.query("select path,body_size,content_type from caller_artifact where run_id=? and id=?",(rs,n)->{Map<String,Object> v=new LinkedHashMap<>();Path p=data.resolve(rs.getString(1)).normalize();if(!p.startsWith(data.resolve("bodies")))throw new IllegalStateException("Invalid managed path");v.put("path",p);v.put("size",rs.getLong(2));v.put("contentType",rs.getString(3));return v;},runId,id);
        if(rows.isEmpty())throw new Missing("Artifact not found");return rows.get(0);
    }
    public List<String> expiredRuns(Instant before){return jdbc.query("select id from caller_run where started_at<? and status in ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')",(rs,n)->rs.getString(1),FIXED_INSTANT.format(before));}
    public void markCleanup(String id){try{jdbc.update("insert into caller_cleanup(run_id) values(?)",id);}catch(DuplicateKeyException ignored){}}
    public List<String> pendingCleanup(){return jdbc.query("select run_id from caller_cleanup",(rs,n)->rs.getString(1));}
    public synchronized void finishCleanup(String id){jdbc.update("delete from caller_artifact where run_id=?",id);jdbc.update("delete from caller_result where run_id=?",id);jdbc.update("delete from caller_run where id=?",id);jdbc.update("delete from caller_cleanup where run_id=?",id);}
    public Set<Path> artifactPaths(){return new HashSet<>(jdbc.query("select path from caller_artifact",(rs,n)->data.resolve(rs.getString(1)).normalize()));}
    private static String canonical(String value){return FIXED_INSTANT.format(Instant.parse(value));}
    private static String like(String value){return "%"+(value==null?"":value.toLowerCase(Locale.ROOT))+"%";}
    public static final class Missing extends RuntimeException {public Missing(String m){super(m);}}
    public static final class Conflict extends RuntimeException {public Conflict(String m){super(m);}}
}
