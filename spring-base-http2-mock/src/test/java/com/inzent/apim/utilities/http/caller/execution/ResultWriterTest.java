package com.inzent.apim.utilities.http.caller.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResultWriterTest {
    @TempDir Path data;
    @Test void replayRepairsRecoveryPendingAndNeverConcealsKnownLoss()throws Exception{
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        var store=new CallerStore(jdbc,new ObjectMapper().findAndRegisterModules(),data);store.initialize();
        var scenario=store.createScenario(Scenario.example());var state=new RunState(scenario,1);store.saveRun(state.snapshot());
        jdbc.execute("alter table caller_result rename to hidden_result");
        try(var writer=new ResultWriter(store,state,data)){
            writer.offer(new LinkedHashMap<>(Map.of("id","r1","runId",state.id,"pathId","p1","success",true)));
            long until=System.nanoTime()+5_000_000_000L;while(!state.writerFailed&&System.nanoTime()<until)Thread.sleep(10);
            assertTrue(state.writerFailed);
            writer.close();jdbc.execute("alter table hidden_result rename to caller_result");state.status("FAILED");writer.saveTerminal();
        }
        ResultWriter.recover(store,data);
        assertEquals(1,store.results(state.id,null,null,0,20).size());assertEquals("OK",store.run(state.id).get("persistenceState"));
        state.persistenceState="LOST_RESULTS";state.writerFailed=true;
        try(var writer=new ResultWriter(store,state,data)){writer.saveTerminal();}
        ResultWriter.recover(store,data);assertEquals("LOST_RESULTS",store.run(state.id).get("persistenceState"));assertEquals(false,store.run(state.id).get("statisticsComplete"));
    }
    @Test void truncatedCrashTailDoesNotBlockReplayOfCompleteRecords()throws Exception{
        crashTail("{\"payload\":".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void truncatedUtf8CharacterDoesNotPreventJournalRecovery()throws Exception{
        crashTail(new byte[]{'{','"',(byte)0xed,(byte)0x95});
    }
    private void crashTail(byte[] tail)throws Exception{
        var store=new CallerStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")),new ObjectMapper(),data);store.initialize();
        var scenario=store.createScenario(Scenario.example());var state=new RunState(scenario,1);store.saveRun(state.snapshot());
        try(var writer=new ResultWriter(store,state,data)){writer.append(Map.of("type","result","value",Map.of("id","ok","runId",state.id,"pathId","p","success",true)));}
        Files.write(data.resolve("journal").resolve(state.id+".jsonl"),tail,StandardOpenOption.APPEND);
        ResultWriter.recover(store,data);assertEquals(1,store.results(state.id,null,null,0,20).size());assertEquals("LOST_RESULTS",store.run(state.id).get("persistenceState"));
    }
}
