package com.inzent.apim.utilities.hazelcastmonitoring;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.core.*;
import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.collection.ClusterCollector;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Opt-in real SDK lifecycle checks: -Dtest=ResourceLifecycleIT. No GC timing assertions. */
class ResourceLifecycleIT {
    @Test void repeatedCollectionsReleaseOwnedClientsAndObserverWorkers() {
        String cluster="resource-test-"+UUID.randomUUID(); HazelcastInstance member=null;
        var baseline=Set.copyOf(HazelcastClient.getAllHazelcastClients());
        try {
            member=Hazelcast.newHazelcastInstance(ClusterCollectorTest.config(cluster,"node",null));
            member.getMap("orders").put("key","value");
            var o=Options.parse(new String[]{"--monitor.client-config="+Fixtures.clientConfig(cluster,
                "127.0.0.1:"+member.getCluster().getLocalMember().getAddress().getPort())},Fixtures.environment());
            var collector=new ClusterCollector();
            for(int i=0;i<3;i++) {
                var snapshot=collector.collect(o);
                assertThat(snapshot.collectionStatus()).isEqualTo(Snapshot.Status.SUCCESS);
                assertThat(snapshot.activeNodeCount()).isEqualTo(1);
                assertThat(o.clientSettings().getListenerConfigs()).isEmpty();
                assertThat(HazelcastClient.getAllHazelcastClients()).containsExactlyInAnyOrderElementsOf(baseline);
                await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(Thread.getAllStackTraces().keySet())
                    .noneMatch(t->t.isAlive()&&t.getName().equals("hzmon-observer")));
            }
        } finally { if(member!=null)member.shutdown(); }
    }
}
