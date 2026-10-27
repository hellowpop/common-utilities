package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import java.time.Instant;
import java.util.*;

final class Fixtures {
    static Snapshot snapshot(String time, String cluster, String service, String name, Long count) {
        Instant t = Instant.parse(time);
        return new Snapshot(UUID.randomUUID(), cluster, "dev", t, t.plusMillis(20), 1, Snapshot.Status.SUCCESS,
            "UNKNOWN", false, t, true, null, null,
            List.of(new Snapshot.Node(UUID.fromString("415e32eb-9bef-482b-a887-b11dc0e2b992"), "node-a", "127.0.0.1:5701", "OBSERVED_ACTIVE", false)),
            List.of(new Snapshot.ObjectInfo("MAP", service, name, count, count == null ? Snapshot.CountStatus.ERROR : Snapshot.CountStatus.SUCCESS,
                t, count == null ? "TIMEOUT" : null, null)));
    }
}
