package com.inzent.apim.utilities.http.caller.execution;

import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/** One instance per worker; gaps are completion-to-next-start per path, across loop boundaries. */
final class RequestPacer {
    @FunctionalInterface interface Sleeper {void sleep(long nanos)throws InterruptedException;}
    private final long seed;private final int workerIndex;private final LongSupplier clock;private final Sleeper sleeper;
    private final Map<String,Long> completed=new HashMap<>();
    private final Map<String,SplittableRandom> randoms=new HashMap<>();
    RequestPacer(long seed,int workerIndex){this(seed,workerIndex,System::nanoTime,n->TimeUnit.NANOSECONDS.sleep(n));}
    RequestPacer(long seed,int workerIndex,LongSupplier clock,Sleeper sleeper){this.seed=seed;this.workerIndex=workerIndex;this.clock=clock;this.sleeper=sleeper;}
    void completed(String pathId){completed.put(pathId,clock.getAsLong());}
    boolean awaitNext(Scenario.RequestPath path,BooleanSupplier canContinue,LongSupplier durationRemaining)throws InterruptedException{
        if(!canContinue.getAsBoolean())return false;
        Long previous=completed.get(path.id);if(previous==null||"NONE".equals(path.delay.mode))return true;
        long millis=switch(path.delay.mode){
            case "FIXED"->path.delay.fixedMillis;
            case "RANDOM"->randoms.computeIfAbsent(path.id,id->new SplittableRandom(seed^((long)workerIndex*104729)^id.hashCode())).nextLong(path.delay.minMillis,path.delay.maxMillis+1);
            default->throw new IllegalArgumentException("Unknown delay mode");
        };
        long interval=TimeUnit.MILLISECONDS.toNanos(millis);
        while(canContinue.getAsBoolean()){
            long left=interval-(clock.getAsLong()-previous);if(left<=0)return true;
            long budget=durationRemaining.getAsLong();if(budget<=0)return false;
            sleeper.sleep(Math.min(left,budget));
        }
        return false;
    }
}
