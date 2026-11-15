package com.inzent.apim.utilities.http.caller.scenario;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class ScenarioTest {
    @Test void oldJsonDefinitionsKeepLoopExecutionAndNoDelay()throws Exception{
        Scenario s=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"title\":\"legacy\",\"paths\":[{\"requestPath\":\"/\"}]}",Scenario.class);
        assertEquals("LOOPS",s.executionMode);assertEquals(60,s.durationSeconds);assertEquals("NONE",s.paths.get(0).delay.mode);assertEquals(4,new ScenarioValidator().validate(s));
    }
    @Test void durationAndLoopLimitsAreMutuallyExclusive(){
        Scenario s=Scenario.example();s.durationSeconds=0;
        assertEquals(4,new ScenarioValidator().validate(s));
        s.executionMode="DURATION";s.durationSeconds=1;s.loopCount=0;
        assertEquals(-1,new ScenarioValidator().validate(s));
        s.durationSeconds=0;assertThrows(IllegalArgumentException.class,()->new ScenarioValidator().validate(s));
        s.durationSeconds=1;s.rampUpSeconds=1;assertThrows(IllegalArgumentException.class,()->new ScenarioValidator().validate(s));
        s.rampUpSeconds=0;s.durationSeconds=900;assertThrows(IllegalArgumentException.class,()->new ScenarioValidator().validate(s));
        s.durationSeconds=1;s.executionMode="UNKNOWN";assertThrows(IllegalArgumentException.class,()->new ScenarioValidator().validate(s));
    }
    @Test void validatesOnlyActiveDelayParameters(){
        Scenario s=Scenario.example();var d=s.paths.get(0).delay;
        d.fixedMillis=-1;new ScenarioValidator().validate(s);
        d.mode="FIXED";assertThrows(IllegalArgumentException.class,()->new ScenarioValidator().validate(s));
        d.fixedMillis=100;new ScenarioValidator().validate(s);
        d.mode="RANDOM";d.minMillis=50;d.maxMillis=40;assertThrows(IllegalArgumentException.class,()->new ScenarioValidator().validate(s));
        d.maxMillis=60;new ScenarioValidator().validate(s);
        d.maxMillis=600001;assertThrows(IllegalArgumentException.class,()->new ScenarioValidator().validate(s));
    }
    @Test void keepsBasePrefixAndPathQuery() {
        assertEquals("https://example.test/api/orders?q=a%20b", ScenarioValidator.resolve("https://example.test/api/", "/orders?q=a%20b").toString());
        assertThrows(IllegalArgumentException.class, () -> ScenarioValidator.resolve("https://example.test/api", "//evil.test/a"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioValidator.resolve("https://example.test/api", "/../a"));
    }
    @Test void enumeratesEveryCombinationAndSeedReproducesPermutation() {
        Scenario s = Scenario.example();
        s.maxWorkers=2; s.loopCount=3;
        s.paths.get(0).payload.contentLength=List.of(64L,128L);
        s.paths.get(0).payload.chunked=List.of(false,true);
        assertEquals(24, new ScenarioValidator().validate(s));
        PayloadMatrix m=new PayloadMatrix(s.paths.get(0).payload);
        assertEquals(4,m.size());
        assertEquals(64,m.at(0).contentLength());
        assertEquals(128,m.at(1).contentLength());
        Set<Long> seen=new HashSet<>();
        for(long i=0;i<4;i++) {long a=m.index(i,true,123); seen.add(a); assertEquals(a,m.index(i,true,123));}
        assertEquals(Set.of(0L,1L,2L,3L),seen);
    }
    @Test void rejectsInvalidPayloadAndUnboundedRequests() {
        Scenario small=Scenario.example(); small.paths.get(0).payload.contentLength=List.of(1L);
        assertThrows(IllegalArgumentException.class, ()->new ScenarioValidator().validate(small));
        Scenario bad=Scenario.example(); bad.paths.get(0).payload.contentLength=List.of();
        assertThrows(IllegalArgumentException.class, ()->new ScenarioValidator().validate(bad));
        Scenario big=Scenario.example(); big.maxWorkers=32; big.loopCount=100001;
        assertThrows(IllegalArgumentException.class, ()->new ScenarioValidator().validate(big));
    }
}
