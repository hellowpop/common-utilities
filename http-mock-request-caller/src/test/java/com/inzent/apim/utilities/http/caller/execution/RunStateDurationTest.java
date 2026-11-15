package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RunStateDurationTest {
    @Test void durationAdmissionIsClosedBeforeStartAndAfterExpiry(){
        var scenario=Scenario.example();scenario.executionMode="DURATION";scenario.durationSeconds=1;
        var state=new RunState(scenario,-1);assertFalse(state.tryStartRequest());
        state.startExecution(System.nanoTime()-TimeUnit.SECONDS.toNanos(2));assertFalse(state.tryStartRequest());
        assertNull(state.snapshot().get("planned"));assertEquals(0L,state.started);
    }
    @Test void concurrentWorkersCannotExceedDurationRequestSafetyLimit()throws Exception{
        var scenario=Scenario.example();scenario.executionMode="DURATION";scenario.durationSeconds=60;
        var state=new RunState(scenario,-1);state.startExecution(System.nanoTime());
        var pool=Executors.newFixedThreadPool(8);
        try{
            var jobs=new java.util.ArrayList<Future<?>>();for(int i=0;i<16;i++)jobs.add(pool.submit(()->{while(state.tryStartRequest()){};}));
            for(var job:jobs)job.get(5,TimeUnit.SECONDS);
            assertEquals(100000L,state.started);assertEquals("REQUEST_LIMIT",state.stop.get());assertTrue(state.error.contains("100000"));
        }finally{pool.shutdownNow();}
    }
}
