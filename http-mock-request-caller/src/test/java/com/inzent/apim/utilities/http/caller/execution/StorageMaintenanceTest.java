package com.inzent.apim.utilities.http.caller.execution;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StorageMaintenanceTest {
    @TempDir Path data;
    @Test void expiresEndedRunsAndRemovesOrphansWithinManagedPaths()throws Exception{
        var store=new CallerStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")),new ObjectMapper(),data);store.initialize();
        String old=Instant.now().minus(Duration.ofDays(10)).toString();
        store.saveRun(new LinkedHashMap<>(Map.of("id","old-run","scenarioId","s","title","old","status","COMPLETED","startedAt",old,"endedAt",old)));
        store.saveRun(new LinkedHashMap<>(Map.of("id","active-run","scenarioId","s","title","active","status","RUNNING","startedAt",old)));
        Path oldBody=data.resolve("bodies/old-run/x.bin"),activeBody=data.resolve("bodies/active-run/y.bin"),orphan=data.resolve("bodies/orphan/z.bin"),temp=data.resolve("temp/a.bin");
        for(Path p:List.of(oldBody,activeBody,orphan,temp)){Files.createDirectories(p.getParent());Files.writeString(p,"body");}
        store.artifact("active-run","y",activeBody,4,"application/octet-stream");
        Path sentinel=data.resolve("keep.txt");Files.writeString(sentinel,"keep");
        var maintenance=new StorageMaintenance(store,data,7);maintenance.sweep(Set.of("active-run"),true);
        assertFalse(Files.exists(oldBody));assertFalse(Files.exists(orphan));assertFalse(Files.exists(temp));assertTrue(Files.exists(activeBody));assertTrue(Files.exists(sentinel));
        assertThrows(CallerStore.Missing.class,()->store.run("old-run"));assertEquals("RUNNING",store.run("active-run").get("status"));
        assertTrue(store.pendingCleanup().isEmpty());
    }
}
