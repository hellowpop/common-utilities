package com.inzent.apim.utilities.hazelcastmonitoring.collection;

import com.hazelcast.cluster.*;
import com.hazelcast.core.*;
import java.util.concurrent.*;

/** Connection and initial membership can arrive in either order on different client versions. */
final class ClientReadiness {
    private final CountDownLatch connected=new CountDownLatch(1);
    private final CountDownLatch members=new CountDownLatch(1);
    private final LifecycleListener lifecycle=event -> {
        if(event.getState()==LifecycleEvent.LifecycleState.CLIENT_CONNECTED)connected.countDown();
    };
    private final InitialMembershipListener membership=new InitialMembershipListener() {
        @Override public void init(InitialMembershipEvent event) { if(!event.getMembers().isEmpty())members.countDown(); }
        @Override public void memberAdded(MembershipEvent event) { members.countDown(); }
        @Override public void memberRemoved(MembershipEvent event) {}
    };
    LifecycleListener lifecycleListener() { return lifecycle; }
    InitialMembershipListener membershipListener() { return membership; }
    void await(long deadline) throws InterruptedException,TimeoutException {
        await(connected,deadline); await(members,deadline);
    }
    private void await(CountDownLatch latch,long deadline) throws InterruptedException,TimeoutException {
        long remaining=deadline-System.nanoTime();
        if(remaining<=0||!latch.await(remaining,TimeUnit.NANOSECONDS))throw new TimeoutException("Client connection or initial membership unavailable");
    }
}
