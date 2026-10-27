package com.inzent.apim.utilities.hazelcastmonitoring.collection;

import com.hazelcast.cluster.*;
import com.hazelcast.core.LifecycleEvent;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientReadinessTest {
    @Test void connectedEventAloneDoesNotMakeAnEmptyMemberListReady() throws Exception {
        var readiness=new ClientReadiness();
        readiness.lifecycleListener().stateChanged(new LifecycleEvent(LifecycleEvent.LifecycleState.CLIENT_CONNECTED));
        readiness.membershipListener().init(new InitialMembershipEvent(mock(Cluster.class),Set.of()));
        assertThatThrownBy(()->readiness.await(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(5))).isInstanceOf(TimeoutException.class);
        readiness.membershipListener().init(new InitialMembershipEvent(mock(Cluster.class),Set.of(mock(Member.class))));
        readiness.await(System.nanoTime()+TimeUnit.SECONDS.toNanos(1));
    }
    @Test void initialMembersStillRequireAConnectedClient() throws Exception {
        var readiness=new ClientReadiness();
        readiness.membershipListener().init(new InitialMembershipEvent(mock(Cluster.class),Set.of(mock(Member.class))));
        assertThatThrownBy(()->readiness.await(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(5))).isInstanceOf(TimeoutException.class);
        readiness.lifecycleListener().stateChanged(new LifecycleEvent(LifecycleEvent.LifecycleState.CLIENT_CONNECTED));
        readiness.await(System.nanoTime()+TimeUnit.SECONDS.toNanos(1));
    }
}
