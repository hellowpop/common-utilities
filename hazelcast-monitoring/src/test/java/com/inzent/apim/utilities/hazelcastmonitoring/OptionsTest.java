package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class OptionsTest {
    @Test void modeFlagsAreExclusiveAndTakeNoValues() {
        for(String flag:java.util.List.of("collect","report","init","install")) {
            assertThatThrownBy(()->parse("--"+flag+"=true")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->parse("--mode="+flag)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->parse("--"+flag,"--"+flag)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->parse("--collect","--install")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Options.installRequested(new String[]{"--install","--report"})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void everyExplicitFlagOverridesConfiguredInstall() {
        for(var mode:Options.Mode.values())assertThat(Options.parse(new String[]{"--"+mode.name().toLowerCase(java.util.Locale.ROOT)},new MockEnvironment().withProperty("mode","install")).mode()).isEqualTo(mode);
    }
    @Test void explicitCollectModeOverridesConfiguredMode() {
        assertThat(Options.parse(new String[]{"--collect"},new MockEnvironment().withProperty("mode","report")).mode()).isEqualTo(Options.Mode.COLLECT);
    }
    private Options parse(String... args) { return Options.parse(args, new MockEnvironment()); }
    @Test void defaultIsSingleCollectionAndEnvironmentIsApplied() {
        var o = Options.parse(new String[0], new MockEnvironment().withProperty("monitor.cluster-name", "production"));
        assertThat(o.mode()).isEqualTo(Options.Mode.COLLECT);
        assertThat(o.clusterId()).isEqualTo("production");
    }
    @Test void rejectsInvalidModeAndDuplicateOrUnknownArgumentsBeforeConnections() {
        assertThatThrownBy(() -> parse("--mode=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("--mode=daemon")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("--report", "--init")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("--monitor.typo=yes")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("positional")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresOffsetsAndValidHalfOpenReportRange() {
        assertThatThrownBy(() -> parse("--report", "--monitor.start-time=2026-10-27T00:00:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("--monitor.start-time=2026-10-27T00:00:00Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("--report", "--monitor.start-time=2026-10-27T00:00:00Z", "--monitor.end-time=2026-10-27T00:00:00Z")).isInstanceOf(IllegalArgumentException.class);
        assertThat(parse("--report", "--monitor.start-time=2026-10-27T09:00:00+09:00").start()).hasToString("2026-10-27T00:00:00Z");
    }
    @Test void rejectsUnboundedTimeoutAndMixedClientConfiguration() {
        assertThatThrownBy(() -> parse("--monitor.operation-timeout=0s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("--monitor.client-config=a.yml", "--monitor.members=localhost:5701")).isInstanceOf(IllegalArgumentException.class);
    }
}
