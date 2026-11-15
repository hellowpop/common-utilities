package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RequestPacerTest {
    @Test void firstRequestIsImmediateAndElapsedOtherWorkCountsTowardFixedGap()throws Exception{
        AtomicLong clock=new AtomicLong();List<Long> sleeps=new ArrayList<>();
        var pacer=new RequestPacer(7,1,clock::get,n->{sleeps.add(n);clock.addAndGet(n);});
        var p=Scenario.example().paths.get(0);p.delay.mode="FIXED";p.delay.fixedMillis=100;
        assertTrue(pacer.awaitNext(p,()->true,()->Long.MAX_VALUE));assertTrue(sleeps.isEmpty());
        pacer.completed(p.id);clock.addAndGet(25_000_000);
        assertTrue(pacer.awaitNext(p,()->true,()->Long.MAX_VALUE));assertEquals(List.of(75_000_000L),sleeps);
        pacer.completed(p.id);clock.addAndGet(150_000_000);
        pacer.awaitNext(p,()->true,()->Long.MAX_VALUE);assertEquals(1,sleeps.size());
        var another=Scenario.example().paths.get(0);pacer.awaitNext(another,()->true,()->Long.MAX_VALUE);assertEquals(1,sleeps.size());
    }
    @Test void randomGapsAreInclusiveInRangeAndRepeatWithTheSameSeed()throws Exception{
        var p=Scenario.example().paths.get(0);p.delay.mode="RANDOM";p.delay.minMillis=40;p.delay.maxMillis=60;
        List<Long> first=new ArrayList<>(),second=new ArrayList<>();
        for(var gaps:List.of(first,second)){
            AtomicLong clock=new AtomicLong();var pacer=new RequestPacer(42,2,clock::get,n->{gaps.add(n);clock.addAndGet(n);});
            for(int i=0;i<30;i++){pacer.completed(p.id);assertTrue(pacer.awaitNext(p,()->true,()->Long.MAX_VALUE));}
        }
        assertEquals(first,second);assertTrue(first.stream().allMatch(n->n>=40_000_000&&n<=60_000_000));assertTrue(new HashSet<>(first).size()>1);
    }
    @Test void durationCapsDelayAndUserCancellationInterruptsWait()throws Exception{
        AtomicLong clock=new AtomicLong();var p=Scenario.example().paths.get(0);p.delay.mode="FIXED";p.delay.fixedMillis=100;
        var pacer=new RequestPacer(1,1,clock::get,clock::addAndGet);pacer.completed(p.id);
        assertFalse(pacer.awaitNext(p,()->clock.get()<30_000_000,()->Math.max(0,30_000_000-clock.get())));assertEquals(30_000_000,clock.get());
        var interrupted=new RequestPacer(1,1,clock::get,n->{throw new InterruptedException();});interrupted.completed(p.id);
        assertThrows(InterruptedException.class,()->interrupted.awaitNext(p,()->true,()->Long.MAX_VALUE));
    }
}
