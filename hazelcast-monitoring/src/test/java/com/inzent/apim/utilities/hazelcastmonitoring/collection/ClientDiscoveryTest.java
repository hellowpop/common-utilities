package com.inzent.apim.utilities.hazelcastmonitoring.collection;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ClientDiscoveryTest {
    @TempDir Path temp;
    private Options options(String... args) { return Options.parse(args,new MockEnvironment()); }
    private Path yaml(String name,String text) throws Exception { Path p=temp.resolve(name); Files.writeString(p,text); return p; }
    @Test void loadsNativeYamlAndPreservesConnectionProperties() throws Exception {
        var file=yaml("client settings.yaml","""
            hazelcast-client:
              cluster-name: production
              properties:
                hazelcast.client.invocation.timeout.seconds: 42
              connection-strategy:
                connection-retry:
                  cluster-connect-timeout-millis: 19000
              network:
                smart-routing: false
                cluster-members:
                  - host-a:5701
                  - host-b:5702
            """);
        var o=options("--monitor.client-config="+file);
        Files.delete(file); // Collection uses the validated in-memory config, without a second read.
        var c=new ClusterCollector().clientConfig(o);
        assertThat(o.clusterId()).isEqualTo("production");
        assertThat(c.getNetworkConfig().getAddresses()).containsExactly("host-a:5701","host-b:5702");
        assertThat(c.getNetworkConfig().isSmartRouting()).isFalse();
        assertThat(c.getProperty("hazelcast.client.invocation.timeout.seconds")).isEqualTo("42");
        assertThat(c.getConnectionStrategyConfig().getConnectionRetryConfig().getClusterConnectTimeoutMillis()).isEqualTo(19000);
    }
    @Test void loadsNativeXmlAndCliPathOverridesEnvironment() throws Exception {
        var file=yaml("client.xml","""
            <hazelcast-client xmlns="http://www.hazelcast.com/schema/client-config">
              <cluster-name>xml-cluster</cluster-name>
              <network><cluster-members><address>xml-host:5702</address></cluster-members></network>
            </hazelcast-client>
            """);
        var o=Options.parse(new String[]{"--monitor.client-config="+file},new MockEnvironment().withProperty("monitor.client-config","missing.yml"));
        var c=new ClusterCollector().clientConfig(o);
        assertThat(c.getClusterName()).isEqualTo("xml-cluster");
        assertThat(c.getNetworkConfig().getAddresses()).containsExactly("xml-host:5702");
    }
    @Test void udpComesFromNativeDiscoveryConfiguration() throws Exception {
        var file=yaml("udp.yml","""
            hazelcast-client:
              cluster-name: udp-cluster
              properties:
                hazelcast.discovery.enabled: true
              network:
                discovery-strategies:
                  discovery-strategies:
                    - class: com.hazelcast.spi.discovery.multicast.MulticastDiscoveryStrategy
                      enabled: true
                      properties:
                        group: 239.10.20.30
                        port: 54328
            """);
        var c=new ClusterCollector().clientConfig(options("--monitor.client-config="+file));
        var strategies=c.getNetworkConfig().getDiscoveryConfig().getDiscoveryStrategyConfigs();
        assertThat(strategies).hasSize(1);
        assertThat(strategies.iterator().next().getProperties()).containsEntry("group","239.10.20.30").containsEntry("port","54328");
    }
    @Test void rejectsMissingMalformedAndUnsupportedFilesBeforeDatabaseWork() throws Exception {
        for(Path file:java.util.List.of(temp.resolve("missing.yml"),yaml("bad.yml","hazelcast-client: [broken"),yaml("client.txt","anything")))
            assertThatThrownBy(()->options("--monitor.client-config="+file)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsLegacyConnectionOptionsAndDoesNotReadClientFileForOfflineModes() {
        for(String key:java.util.List.of("cluster-name","members","lookup-mode","udp-group","udp-port")) {
            assertThatThrownBy(()->options("--monitor."+key+"=value")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->Options.parse(new String[]{"--report"},new MockEnvironment().withProperty("monitor."+key,"value")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client-config");
        }
        for(String mode:java.util.List.of("report","init","install"))
            assertThat(options("--"+mode,"--monitor.client-config=missing.xml").clientConfig().getFileName().toString()).isEqualTo("missing.xml");
    }
}
