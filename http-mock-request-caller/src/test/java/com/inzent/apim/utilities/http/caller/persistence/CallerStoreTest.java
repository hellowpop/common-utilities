package com.inzent.apim.utilities.http.caller.persistence;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CallerStoreTest {
    @TempDir Path directory;
    CallerStore store;
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        store=new CallerStore(new JdbcTemplate(source),new ObjectMapper().findAndRegisterModules(),directory);
        store.initialize();
    }
    @Test void keepsImmutableRunSnapshotAndRejectsStaleEdit(){
        Scenario a=store.createScenario(Scenario.example());
        Map<String,Object> run=new LinkedHashMap<>();run.put("id","run-1");run.put("scenarioId",a.id);run.put("title",a.title);run.put("status","RUNNING");run.put("startedAt","2026-11-12T00:00:00Z");run.put("snapshot",a);
        store.saveRun(run);
        a.title="changed";store.updateScenario(a.id,a);
        assertEquals("HTTP scenario",((Map<?,?>)store.run("run-1").get("snapshot")).get("title"));
        a.version=1;assertThrows(CallerStore.Conflict.class,()->store.updateScenario(a.id,a));
        assertEquals(1,store.searchRuns(null,"HTTP",null,null,null,0,20).size());
    }
    @Test void recoversInterruptedExecutionAndDoesNotDuplicateResult(){
        Scenario a=store.createScenario(Scenario.example());
        Map<String,Object> run=new LinkedHashMap<>(Map.of("id","run-2","scenarioId",a.id,"title",a.title,"status","RUNNING","startedAt","2026-11-12T00:00:00Z"));store.saveRun(run);
        var result=new LinkedHashMap<String,Object>(Map.of("id","req-1","runId","run-2","pathId","p1","success",true));
        store.saveResult(result);store.saveResult(result);
        assertEquals(1,store.results("run-2",null,null,0,20).size());
        assertEquals(1,store.recoverInterrupted());
        assertEquals("INTERRUPTED",store.run("run-2").get("status"));
    }
    @Test void filtersTimestampBoundariesAndFindsOldIdempotencyKeys(){
        var a=store.createScenario(Scenario.example());
        Map<String,Object> run=new LinkedHashMap<>(Map.of("id","run-time","scenarioId",a.id,"title","time","status","COMPLETED","startedAt","2026-11-12T00:00:00.500Z","idempotencyKey","original"));store.saveRun(run);
        assertEquals(1,store.searchRuns(null,null,"2026-11-12T00:00:00Z","2026-11-12T00:00:01Z",null,0,20).size());
        assertEquals(0,store.searchRuns(null,null,null,"2026-11-12T00:00:00.500Z",null,0,20).size());
        assertEquals("run-time",store.byIdempotencyKey(a.id,"original").get("id"));
    }
}
