package com.inzent.apim.utilities.hazelcastmonitoring.collection;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.cluster.*;
import com.hazelcast.config.ListenerConfig;
import com.hazelcast.core.*;
import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectorLifecycleTest {
    @TempDir Path temp;
    private Options options(String... extra) throws Exception {
        Path file=temp.resolve("client.yml"); Files.writeString(file,"hazelcast-client:\n  cluster-name: dev\n");
        return Options.parse(extra,new MockEnvironment().withProperty("monitor.client-config",file.toString()));
    }
    private HazelcastInstance client() throws Exception {
        var c=mock(HazelcastInstance.class); var cluster=mock(Cluster.class); var member=mock(Member.class);
        when(member.getUuid()).thenReturn(UUID.randomUUID()); when(member.getAddress()).thenReturn(new Address("127.0.0.1",5701));
        when(cluster.getMembers()).thenReturn(Set.of(member)); when(c.getCluster()).thenReturn(cluster);
        when(c.getDistributedObjects()).thenReturn(List.of()); when(c.getLifecycleService()).thenReturn(mock(LifecycleService.class));
        return c;
    }
    private HazelcastInstance ready(ClientConfig config,HazelcastInstance client) {
        for(ListenerConfig listener:config.getListenerConfigs()) {
            var implementation=listener.getImplementation();
            if(implementation instanceof LifecycleListener l)l.stateChanged(new LifecycleEvent(LifecycleEvent.LifecycleState.CLIENT_CONNECTED));
            if(implementation instanceof InitialMembershipListener l)l.init(new InitialMembershipEvent(client.getCluster(),client.getCluster().getMembers()));
        }
        return client;
    }
    @Test void repeatedCollectionsNeverMutateOrRetainListenersInOriginalConfiguration() throws Exception {
        var o=options(); var user=mock(LifecycleListener.class); o.clientSettings().addListenerConfig(new ListenerConfig(user));
        List<ClientConfig> runs=new ArrayList<>(); var client=client();
        var collector=new ClusterCollector(c->{runs.add(c); return ready(c,client);});
        assertThat(collector.collect(o).collectionStatus()).isEqualTo(Snapshot.Status.SUCCESS);
        assertThat(collector.collect(o).collectionStatus()).isEqualTo(Snapshot.Status.SUCCESS);
        assertThat(o.clientSettings().getListenerConfigs()).hasSize(1);
        assertThat(o.clientSettings().getInstanceName()).isNull();
        assertThat(o.clientSettings().getConnectionStrategyConfig().isAsyncStart()).isFalse();
        assertThat(runs).allSatisfy(c->{assertThat(c).isNotSameAs(o.clientSettings()); assertThat(c.getListenerConfigs()).hasSize(3);});
        verify(client,times(2)).shutdown();
    }
    @Test void shutdownFailurePreservesObservedValuesAndAttemptsForcedTermination() throws Exception {
        var client=client(); doThrow(new IllegalStateException("shutdown")).when(client).shutdown();
        var s=new ClusterCollector(c->ready(c,client)).collect(options());
        assertThat(s.collectionStatus()).isEqualTo(Snapshot.Status.PARTIAL); assertThat(s.activeNodeCount()).isEqualTo(1);
        assertThat(s.errorCode()).isEqualTo("CLIENT_SHUTDOWN_FAILED"); verify(client.getLifecycleService()).terminate();
    }
    @Test void failedCollectionRetainsPrimaryErrorWhenShutdownAlsoFails() throws Exception {
        var client=client(); when(client.getDistributedObjects()).thenThrow(new IllegalArgumentException("inventory"));
        doThrow(new IllegalStateException("shutdown")).when(client).shutdown();
        var s=new ClusterCollector(c->ready(c,client)).collect(options());
        assertThat(s.collectionStatus()).isEqualTo(Snapshot.Status.FAILED);
        assertThat(s.errorCode()).isEqualTo("IllegalArgumentException"); verify(client.getLifecycleService()).terminate();
    }
    @Test void fatalWorkerErrorIsNotConvertedToFailedSnapshotAndCleanupStillRuns() throws Exception {
        var client=client(); var primary=new AssertionError("inventory fatal"); var cleanup=new IllegalStateException("shutdown");
        when(client.getDistributedObjects()).thenThrow(primary); doThrow(cleanup).when(client).shutdown(); var o=options();
        assertThatThrownBy(()->new ClusterCollector(c->ready(c,client)).collect(o)).isSameAs(primary).hasSuppressedException(cleanup);
        verify(client.getLifecycleService()).terminate();
    }
    @Test void fatalShutdownStillAttemptsTerminationAndPropagates() throws Exception {
        var client=client(); var fatal=new AssertionError("shutdown fatal"); var o=options();
        doThrow(fatal).when(client).shutdown();
        assertThatThrownBy(()->new ClusterCollector(c->ready(c,client)).collect(o)).isSameAs(fatal);
        verify(client.getLifecycleService()).terminate();
    }
    @Test void forceTerminationFailureStillPreservesSnapshot() throws Exception {
        var client=client(); doThrow(new IllegalStateException("shutdown")).when(client).shutdown();
        var lifecycle=client.getLifecycleService(); doThrow(new IllegalStateException("terminate")).when(lifecycle).terminate();
        var s=new ClusterCollector(c->ready(c,client)).collect(options());
        assertThat(s.activeNodeCount()).isEqualTo(1); assertThat(s.errorCode()).isEqualTo("CLIENT_SHUTDOWN_FAILED");
    }
    @Test void interruptedCallerKeepsInterruptAfterWorkerCleanup() throws Exception {
        var client=client(); var o=options(); Thread.currentThread().interrupt();
        try {
            var s=new ClusterCollector().collect(client,o);
            assertThat(s.collectionStatus()).isEqualTo(Snapshot.Status.FAILED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        verify(client,never()).shutdown();
    }
    @Test void timedOutWorkerGetsTimeToReleaseResourcesWithoutClosingBorrowedClient() throws Exception {
        var client=client(); var released=new CountDownLatch(1);
        when(client.getDistributedObjects()).thenAnswer(invocation->{
            try { new CountDownLatch(1).await(); return List.of(); }
            finally {
                long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(80);
                while(System.nanoTime()<until) { Thread.interrupted(); java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); }
                released.countDown();
            }
        });
        var s=new ClusterCollector().collect(client,options("--monitor.operation-timeout=100ms"));
        assertThat(s.collectionStatus()).isEqualTo(Snapshot.Status.FAILED); assertThat(released.getCount()).isZero();
        verify(client,never()).shutdown();
    }
}
