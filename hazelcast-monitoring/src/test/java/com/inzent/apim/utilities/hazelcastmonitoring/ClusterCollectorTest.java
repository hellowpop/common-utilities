package com.inzent.apim.utilities.hazelcastmonitoring;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.collection.ClusterCollector;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class ClusterCollectorTest {
    @Test void readsActualCountsFromTwoMembersAndExcludesRegularAndReliableTopics() {
        String cluster="monitor-test-"+UUID.randomUUID();
        HazelcastInstance a=null,b=null,client=null;
        try {
            a=Hazelcast.newHazelcastInstance(config(cluster,"node-a",null));
            int port=a.getCluster().getLocalMember().getAddress().getPort();
            b=Hazelcast.newHazelcastInstance(config(cluster,"node-b","127.0.0.1:"+port));
            var cfg=new ClientConfig().setClusterName(cluster); cfg.getNetworkConfig().addAddress("127.0.0.1:"+port);
            client=HazelcastClient.newHazelcastClient(cfg);
            a.getMap("orders").putAll(Map.of(1,"a",2,"b"));
            a.getList("list").addAll(List.of("a","a","b"));
            a.getSet("set").addAll(List.of("a","a","b"));
            a.getQueue("queue").addAll(List.of("a","b"));
            a.getMultiMap("multi").put("key","a"); a.getMultiMap("multi").put("key","b");
            a.getRingbuffer("ring").add("one");
            a.getMap("empty"); a.getTopic("events");
            a.getReliableTopic("reliable-events").publish("event");
            a.getMap("events").put("retained","value");
            HazelcastInstance observer=client;
            await().atMost(Duration.ofSeconds(15)).until(() -> observer.getCluster().getMembers().size()==2 &&
                observer.getDistributedObjects().stream().filter(object->object instanceof com.hazelcast.topic.ITopic<?>).count()==2);
            var o=Options.parse(new String[]{"--monitor.cluster-name="+cluster},new MockEnvironment());
            var snapshot=new ClusterCollector().collect(client,o);
            assertThat(snapshot.activeNodeCount()).isEqualTo(2);
            assertThat(snapshot.collectionStatus()).isEqualTo(Snapshot.Status.SUCCESS);
            assertThat(snapshot.nodes()).extracting(Snapshot.Node::nodeName).containsExactlyInAnyOrder("node-a","node-b");
            Map<String,Long> counts=new HashMap<>(); snapshot.objects().forEach(v -> counts.put(v.objectName(),v.objectCount()));
            assertThat(counts).containsEntry("orders",2L).containsEntry("list",3L).containsEntry("set",2L).containsEntry("queue",2L)
                .containsEntry("multi",2L).containsEntry("ring",1L).containsEntry("empty",0L).containsEntry("events",1L);
            assertThat(snapshot.objects()).extracting(Snapshot.ObjectInfo::serviceName).doesNotContain("hz:impl:topicService","hz:impl:reliableTopicService");
            assertThat(snapshot.objects()).extracting(Snapshot.ObjectInfo::objectName).doesNotContain("reliable-events");
            assertThat(a.getQueue("queue").size()).isEqualTo(2);
        } finally { if(client!=null)client.shutdown(); if(b!=null)b.shutdown(); if(a!=null)a.shutdown(); }
    }
    @Test void excludedTopicsDoNotSuppressOtherUnsupportedObjects() {
        var client=mock(HazelcastInstance.class); var cluster=mock(com.hazelcast.cluster.Cluster.class);
        when(client.getCluster()).thenReturn(cluster); when(cluster.getMembers()).thenReturn(Set.of());
        var topic=mock(com.hazelcast.topic.ITopic.class); when(topic.getServiceName()).thenReturn("hz:impl:topicService"); when(topic.getName()).thenReturn("events");
        var other=mock(DistributedObject.class); when(other.getServiceName()).thenReturn("custom-service"); when(other.getName()).thenReturn("other");
        when(client.getDistributedObjects()).thenReturn(List.of(topic,other));
        var snapshot=new ClusterCollector().collect(client,Options.parse(new String[0],new MockEnvironment()));
        assertThat(snapshot.collectionStatus()).isEqualTo(Snapshot.Status.SUCCESS);
        assertThat(snapshot.objects()).hasSize(1);
        assertThat(snapshot.objects().get(0).serviceName()).isEqualTo("custom-service");
        assertThat(snapshot.objects().get(0).countStatus()).isEqualTo(Snapshot.CountStatus.UNSUPPORTED);
        assertThat(snapshot.objects().get(0).objectCount()).isNull();
    }
    @Test void unreachableClusterIsFailedWithUnknownNodeCount() {
        var o=Options.parse(new String[]{"--monitor.members=127.0.0.1:1","--monitor.connect-timeout=1s"},new MockEnvironment());
        var snapshot=new ClusterCollector().collect(o);
        assertThat(snapshot.collectionStatus()).isEqualTo(Snapshot.Status.FAILED);
        assertThat(snapshot.activeNodeCount()).isNull();
        assertThat(snapshot.objects()).isEmpty();
    }
    @Test void perObjectErrorsDoNotBecomeZeroOrAbortOtherCounts() {
        var client=mock(HazelcastInstance.class); var cluster=mock(com.hazelcast.cluster.Cluster.class);
        when(client.getCluster()).thenReturn(cluster); when(cluster.getMembers()).thenReturn(Set.of());
        var broken=mock(com.hazelcast.map.IMap.class); when(broken.getServiceName()).thenReturn("map"); when(broken.getName()).thenReturn("a-broken");
        when(broken.size()).thenThrow(new IllegalStateException("sensitive remote message"));
        var healthy=mock(com.hazelcast.map.IMap.class); when(healthy.getServiceName()).thenReturn("map"); when(healthy.getName()).thenReturn("b-healthy"); when(healthy.size()).thenReturn(7);
        when(client.getDistributedObjects()).thenReturn(List.of(broken,healthy));
        var snapshot=new ClusterCollector().collect(client,Options.parse(new String[0],new MockEnvironment()));
        assertThat(snapshot.collectionStatus()).isEqualTo(Snapshot.Status.PARTIAL);
        assertThat(snapshot.objects().get(0).objectCount()).isNull();
        assertThat(snapshot.objects().get(0).errorMessage()).doesNotContain("sensitive");
        assertThat(snapshot.objects().get(1).objectCount()).isEqualTo(7L);
    }
    @Test void timedOutCallMarksRemainingObjectsUncollected() {
        var client=mock(HazelcastInstance.class); var cluster=mock(com.hazelcast.cluster.Cluster.class);
        when(client.getCluster()).thenReturn(cluster); when(cluster.getMembers()).thenReturn(Set.of());
        var slow=mock(com.hazelcast.map.IMap.class); when(slow.getServiceName()).thenReturn("map"); when(slow.getName()).thenReturn("a-slow");
        when(slow.size()).thenAnswer(call -> { Thread.sleep(5000); return 2; });
        var next=mock(com.hazelcast.map.IMap.class); when(next.getServiceName()).thenReturn("map"); when(next.getName()).thenReturn("b-next");
        when(client.getDistributedObjects()).thenReturn(List.of(slow,next));
        var snapshot=new ClusterCollector().collect(client,Options.parse(new String[]{"--monitor.operation-timeout=200ms"},new MockEnvironment()));
        assertThat(snapshot.collectionStatus()).isEqualTo(Snapshot.Status.PARTIAL);
        assertThat(snapshot.objects()).extracting(Snapshot.ObjectInfo::countStatus).containsExactly(Snapshot.CountStatus.ERROR,Snapshot.CountStatus.NOT_COLLECTED);
        assertThat(snapshot.objects().get(0).errorCode()).isEqualTo("TIMEOUT");
    }
    @Test void wholeCollectionBudgetIncludesInitialConnection() {
        var o=Options.parse(new String[]{"--monitor.members=127.0.0.1:1","--monitor.connect-timeout=5s","--monitor.collection-timeout=200ms"},new MockEnvironment());
        long start=System.nanoTime();
        var snapshot=new ClusterCollector().collect(o);
        assertThat(snapshot.collectionStatus()).isEqualTo(Snapshot.Status.FAILED);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
    }
    static Config config(String cluster,String node,String address) {
        var c=new Config().setClusterName(cluster);
        c.getMemberAttributeConfig().setAttribute("monitor.node-name",node);
        c.setProperty("hazelcast.operation.thread.count","2").setProperty("hazelcast.operation.generic.thread.count","2")
            .setProperty("hazelcast.io.input.thread.count","1").setProperty("hazelcast.io.output.thread.count","1")
            .setProperty("hazelcast.partition.count","17").setProperty("hazelcast.phone.home.enabled","false")
            .setProperty("hazelcast.logging.type","slf4j");
        c.getJetConfig().setEnabled(false);
        c.getNetworkConfig().setPort(0); c.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        c.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
        if(address!=null)c.getNetworkConfig().getJoin().getTcpIpConfig().addMember(address);
        return c;
    }
}
