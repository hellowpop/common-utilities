package com.inzent.apim.utilities.hazelcastmonitoring;

import com.hazelcast.config.DiscoveryStrategyConfig;
import com.hazelcast.core.*;
import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.collection.ClusterCollector;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import java.net.DatagramSocket;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: requires UDP multicast on the host; run with -Dtest=UdpDiscoveryIT. */
class UdpDiscoveryIT {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    @Test void discoversAndCollectsWithoutTcpSeedAddresses() throws Exception {
        int port;
        try(var socket=new DatagramSocket(0)) { port=socket.getLocalPort(); }
        String group="239.255.17.42", cluster="udp-monitor-"+UUID.randomUUID();
        var config=ClusterCollectorTest.config(cluster,"udp-node",null);
        config.setProperty("hazelcast.discovery.enabled","true");
        var join=config.getNetworkConfig().getJoin();
        join.getTcpIpConfig().setEnabled(false); join.getAutoDetectionConfig().setEnabled(false);
        join.getDiscoveryConfig().addDiscoveryStrategyConfig(new DiscoveryStrategyConfig(
            "com.hazelcast.spi.discovery.multicast.MulticastDiscoveryStrategy").addProperty("group",group).addProperty("port",Integer.toString(port)));
        HazelcastInstance member=null;
        try {
            member=Hazelcast.newHazelcastInstance(config);
            member.getMap("udp-orders").put("key","value");
            var file=temp.resolve("hazelcast-client.yml");
            try(var input=getClass().getResourceAsStream("/install/hazelcast-client-multicast.yml")) {
                var template=new String(java.util.Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                java.nio.file.Files.writeString(file,template.replace("cluster-name: dev","cluster-name: "+cluster)
                    .replace("224.2.2.3",group).replace("54327",Integer.toString(port)));
            }
            var options=Options.parse(new String[]{"--monitor.client-config="+file,
                "--monitor.connect-timeout=20s","--monitor.collection-timeout=25s"},new MockEnvironment());
            var snapshot=new ClusterCollector().collect(options);
            assertThat(snapshot.collectionStatus()).withFailMessage("%s: %s",snapshot.errorCode(),snapshot.errorMessage()).isEqualTo(Snapshot.Status.SUCCESS);
            assertThat(snapshot.activeNodeCount()).isEqualTo(1);
            assertThat(snapshot.objects()).anySatisfy(o->{assertThat(o.objectName()).isEqualTo("udp-orders"); assertThat(o.objectCount()).isEqualTo(1);});
        } finally { if(member!=null)member.shutdown(); }
    }
}
