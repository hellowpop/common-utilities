package com.inzent.apim.utilities.http.caller.execution;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class DeadlineGuardTest {
    @Test void disarmingBlocksLateTimerInterrupt(){
        var timer=Executors.newSingleThreadScheduledExecutor();
        try{var guard=new DeadlineGuard(timer,TimeUnit.SECONDS.toNanos(10));guard.close();guard.expire();assertFalse(Thread.currentThread().isInterrupted());}
        finally{timer.shutdownNow();Thread.interrupted();}
    }
}
