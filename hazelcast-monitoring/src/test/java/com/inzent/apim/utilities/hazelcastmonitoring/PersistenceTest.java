package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.persistence.SnapshotStore;
import com.inzent.apim.utilities.hazelcastmonitoring.schema.SchemaManager;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class PersistenceTest {
    DriverManagerDataSource db;
    SchemaManager schema;
    @BeforeEach void setup() {
        db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        schema = new SchemaManager(db);
    }
    @Test void persistsAggregateWithUtcAndFiltersByClusterAndHalfOpenInterval() {
        schema.update();
        var first = Fixtures.snapshot("2026-10-27T00:00:00.123456Z", "a", "map", "orders", 0L);
        var end = Fixtures.snapshot("2026-10-28T00:00:00Z", "a", "map", "orders", 9L);
        try (var store = new SnapshotStore(db, true)) {
            store.save(first, Path.of("snapshot.yml")); store.save(end, Path.of("end.yml"));
            store.save(Fixtures.snapshot("2026-10-27T00:00:00Z", "b", "map", "orders", 5L), Path.of("b.yml"));
            assertThat(store.load(first.id())).isEqualTo(first);
            assertThat(store.pending("a")).hasSize(2);
            var options = Options.parse(new String[]{"--report", "--monitor.cluster-id=a", "--monitor.start-time=2026-10-27T00:00:00Z", "--monitor.end-time=2026-10-28T00:00:00Z"}, new MockEnvironment());
            assertThat(store.report(options)).containsExactly(first);
            store.markExport(first.id(), "EXPORTED");
            assertThat(store.pending("a")).extracting(SnapshotStore.Pending::id).containsExactly(end.id());
        }
    }
    @Test void initRecreatesOutdatedSchemaAndPreservesUnrelatedTables() throws Exception {
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("create table external_business_data (id integer)");
            s.execute("insert into external_business_data values (42)");
            s.execute("create table hzmon_monitor_summary (old_column integer)");
        }
        schema.reset(); schema.check(); schema.update();
        try (var store = new SnapshotStore(db, true)) { assertThat(store.pending("a")).isEmpty(); }
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery("select id from external_business_data")) {
            assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(42);
        }
        schema.reset(); schema.check();
    }
    @Test void reportDoesNotInitializeMissingSchemaAndLimitsDoNotSilentlyTruncate() throws Exception {
        assertThatThrownBy(schema::check).isInstanceOf(IllegalStateException.class);
        schema.update();
        try (var store = new SnapshotStore(db, true)) {
            store.save(Fixtures.snapshot("2026-10-27T00:00:00Z", "a", "map", "one", 1L), Path.of("1.yml"));
            store.save(Fixtures.snapshot("2026-10-27T01:00:00Z", "a", "map", "two", 2L), Path.of("2.yml"));
            var o = Options.parse(new String[]{"--report", "--monitor.cluster-id=a", "--monitor.max-report-runs=1"}, new MockEnvironment());
            assertThatThrownBy(() -> store.report(o)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void keysetPagesPreserveRunsWithTheSameTimestamp() {
        schema.update();
        try(var store=new SnapshotStore(db,true)) {
            for(int i=0;i<102;i++)store.save(Fixtures.snapshot("2026-10-27T00:00:00Z","a","map","orders",(long)i),Path.of(i+".yml"));
            var rows=store.report(Options.parse(new String[]{"--report","--monitor.cluster-id=a"},new MockEnvironment()));
            assertThat(rows).hasSize(102);
            assertThat(rows.stream().mapToLong(s -> s.objects().get(0).objectCount()).sum()).isEqualTo(5151);
            assertThat(rows.stream().map(s -> s.id()).distinct().count()).isEqualTo(102);
        }
    }
}
