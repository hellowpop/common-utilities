package com.inzent.apim.utilities.hazelcastmonitoring;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.collection.ClusterCollector;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import java.net.DatagramSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Opt-in: real ordinary member multicast, with no Discovery SPI on the servers. */
class MemberMulticastIT {
    @TempDir Path temp;
    @Test void collectsWholeMulticastClusterUsingOneNativeClientSeedWithoutJoiningAsMember() throws Exception {
        int multicastPort;
        try(var socket=new DatagramSocket(0)) { multicastPort=socket.getLocalPort(); }
        String cluster="member-multicast-"+UUID.randomUUID();
        var baseline=Set.copyOf(HazelcastClient.getAllHazelcastClients());
        HazelcastInstance a=null,b=null;
        try {
            a=Hazelcast.newHazelcastInstance(config(cluster,"a",multicastPort));
            b=Hazelcast.newHazelcastInstance(config(cluster,"b",multicastPort));
            var first=a; var second=b;
            await().atMost(Duration.ofSeconds(20)).until(()->first.getCluster().getMembers().size()==2&&second.getCluster().getMembers().size()==2);
            a.getMap("orders").putAll(Map.of("one","a","two","b"));
            Path file=temp.resolve("hazelcast-client.yml");
            Files.writeString(file,"""
                hazelcast-client:
                  cluster-name: %s
                  network:
                    auto-detection:
                      enabled: false
                    cluster-members:
                      - 127.0.0.1:%d
                """.formatted(cluster,a.getCluster().getLocalMember().getAddress().getPort()));
            var o=Options.parse(new String[]{"--monitor.client-config="+file},new MockEnvironment());
            var s=new ClusterCollector().collect(o);
            assertThat(s.collectionStatus()).withFailMessage("%s: %s",s.errorCode(),s.errorMessage()).isEqualTo(Snapshot.Status.SUCCESS);
            assertThat(s.activeNodeCount()).isEqualTo(2);
            assertThat(s.objects()).anySatisfy(object->{assertThat(object.objectName()).isEqualTo("orders"); assertThat(object.objectCount()).isEqualTo(2);});
            assertThat(a.getCluster().getMembers()).hasSize(2); assertThat(b.getCluster().getMembers()).hasSize(2);
            assertThat(HazelcastClient.getAllHazelcastClients()).containsExactlyInAnyOrderElementsOf(baseline);
        } finally { if(b!=null)b.shutdown(); if(a!=null)a.shutdown(); }
    }
    private Config config(String cluster,String node,int port) {
        var c=ClusterCollectorTest.config(cluster,node,null); var join=c.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false); join.getTcpIpConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(true).setMulticastGroup("239.255.21.42").setMulticastPort(port).setMulticastTimeoutSeconds(2);
        assertThat(join.getDiscoveryConfig().getDiscoveryStrategyConfigs()).isEmpty();
        return c;
    }
}
