package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.export.YamlExporter;
import com.inzent.apim.utilities.hazelcastmonitoring.report.HtmlReport;
import com.inzent.apim.utilities.hazelcastmonitoring.persistence.SnapshotStore;
import com.inzent.apim.utilities.hazelcastmonitoring.schema.SchemaManager;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

class ExportReportTest {
    @TempDir Path temp;
    @Test void exportsTimeIndexedYamlAndRecoversWithoutOverwritingConflictingContent() throws Exception {
        var snapshot=Fixtures.snapshot("2026-10-27T00:00:00.123456Z","a","map","orders",0L);
        var exporter=new YamlExporter(); Path path=exporter.path(snapshot,temp);
        exporter.export(snapshot,path); exporter.export(snapshot,path);
        Map<?,?> root=new Yaml().load(Files.readString(path));
        assertThat(root.get("schemaVersion")).isEqualTo(2);
        assertThat(root.get("timeZone")).isEqualTo("Asia/Seoul");
        var value=(Map<?,?>)((Map<?,?>)root.get("snapshots")).get("2026-10-27 09:00:00");
        assertThat(value.get("completedAt")).isEqualTo("2026-10-27 09:00:00");
        assertThat(value.get("objectInventoryObservedAt")).isEqualTo("2026-10-27 09:00:00");
        assertThat(((Map<?,?>)((List<?>)value.get("objects")).get(0)).get("observedAt")).isEqualTo("2026-10-27 09:00:00");
        assertThat(path.getFileName().toString()).isEqualTo("2026-10-27_09-00-00_"+snapshot.id()+".yml");
        Files.writeString(path,"different: true");
        assertThatThrownBy(() -> exporter.export(snapshot,path)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(path)).isEqualTo("different: true");
    }
    @Test void recoversDbCommittedSnapshotAfterFileFailure() throws Exception {
        var db=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new SchemaManager(db).update(); var o=Options.parse(new String[]{"--monitor.cluster-id=a"},new MockEnvironment());
        var snapshot=Fixtures.snapshot("2026-10-27T00:00:00Z","a","map","orders",1L);
        Path target=temp.resolve("recover.yml");
        try(var store=new SnapshotStore(db,true)) {
            store.save(snapshot,target); store.markExport(snapshot.id(),"ERROR");
            assertThat(new YamlExporter().recover(store,o)).isZero();
            assertThat(store.pending("a")).isEmpty(); assertThat(target).exists();
        }
    }
    @Test void reportIsOfflineEscapesNamesAndKeepsMissingValuesAsGaps() throws Exception {
        String dangerous="</script><img src=x onerror=alert(1)>";
        var first=Fixtures.snapshot("2026-10-27T00:00:00Z","a","map",dangerous,0L);
        var gap=Fixtures.snapshot("2026-10-27T01:00:00Z","a","map",dangerous,null);
        var last=Fixtures.snapshot("2026-10-27T02:00:00Z","a","map",dangerous,3L);
        Path path=temp.resolve("report.html");
        var o=Options.parse(new String[]{"--report","--monitor.cluster-id=a","--monitor.report-file="+path},new MockEnvironment());
        new HtmlReport().write(List.of(first,gap,last),o);
        String html=Files.readString(path);
        assertThat(html).contains("<svg", "data-status=\"ERROR\"", "&lt;/script&gt;", "2026-10-27 09:00:00", "2026-10-27 11:00:00");
        assertThat(html).doesNotContain("<img src=x", "<script src=", "fetch(", "https://", "http://");
        assertThatThrownBy(() -> new HtmlReport().write(List.of(),o)).isInstanceOf(java.io.IOException.class);
    }
    @Test void emptyReportStillContainsUsefulMessage() throws Exception {
        Path path=temp.resolve("empty.html");
        new HtmlReport().write(List.of(),Options.parse(new String[]{"--report","--monitor.report-file="+path},new MockEnvironment()));
        assertThat(Files.readString(path)).contains("조회 결과 없음");
    }
    @Test void partitionsBySeoulDateAndSeparatesRunsWithinTheSameSecond() {
        var exporter=new YamlExporter();
        var a=Fixtures.snapshot("2026-10-27T15:57:19.123456Z","a","map","orders",1L);
        var b=Fixtures.snapshot("2026-10-27T15:57:19.654321Z","a","map","orders",1L);
        assertThat(exporter.path(a,temp).getParent()).isEqualTo(temp.resolve("2026/10/28"));
        assertThat(exporter.path(a,temp)).isNotEqualTo(exporter.path(b,temp));
    }
    @Test void acceptsMatchingLegacyYamlWithoutRewritingButRejectsChangedContent() throws Exception {
        var s=Fixtures.snapshot("2026-10-27T06:57:19.123456Z","a","map","orders",1L);
        var exporter=new YamlExporter(); Path path=temp.resolve("legacy.yml");
        exporter.export(s,path);
        Map<String,Object> root=new Yaml().load(Files.readString(path));
        Map<String,Object> snapshots=(Map<String,Object>)root.get("snapshots");
        Map<String,Object> value=(Map<String,Object>)snapshots.values().iterator().next();
        value.put("completedAt",s.completedAt().toString());
        value.put("objectInventoryObservedAt",s.objectInventoryObservedAt().toString());
        ((Map<String,Object>)((List<?>)value.get("objects")).get(0)).put("observedAt",s.objects().get(0).observedAt().toString());
        root.put("schemaVersion",1); root.remove("timeZone");
        root.put("snapshots",Map.of(s.executedAt().toString(),value));
        String legacy=new Yaml().dump(root); Files.writeString(path,legacy);
        exporter.export(s,path);
        assertThat(Files.readString(path)).isEqualTo(legacy);
        value.put("activeNodeCount",7); Files.writeString(path,new Yaml().dump(root));
        assertThatThrownBy(() -> exporter.export(s,path)).isInstanceOf(java.io.IOException.class);
    }
    @Test void reportHonorsConfiguredZoneWithNewFormatAndParsesLocalPeriod() throws Exception {
        Path path=temp.resolve("utc.html");
        var o=Options.parse(new String[]{"--report","--monitor.report-zone=UTC","--monitor.report-file="+path,
            "--monitor.start-time=2026-10-27 15:57:19","--monitor.end-time=2026-10-27 15:57:20"},new MockEnvironment());
        assertThat(o.start()).isEqualTo(java.time.Instant.parse("2026-10-27T06:57:19Z"));
        new HtmlReport().write(List.of(Fixtures.snapshot("2026-10-27T06:57:19.123456Z","a","map","orders",1L)),o);
        assertThat(Files.readString(path)).contains("2026-10-27 06:57:19").doesNotContain("2026-10-27T06:57:19");
    }
}
