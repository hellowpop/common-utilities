package com.inzent.apim.utilities.http.caller.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RequestSearchTest {
    @TempDir Path directory;
    CallerStore store;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        store = new CallerStore(jdbc, new ObjectMapper(), directory);
        store.initialize();
    }

    @Test void combinesFiltersBeforePaginationAndIncludesTimeBoundaries() {
        store.saveResult(result("a", "run", 200, 1, 999999));
        store.saveResult(result("b", "run", 500, 2, 1000000));
        store.saveResult(result("c", "run", 500, 2, 2000000));
        store.saveResult(result("d", "run", 500, 2, 2000001));
        store.saveResult(result("e", "other", 500, 2, 1000000));
        Assertions.assertEquals(List.of("b"), ids(store.results("run", "p", false, 500, 2, 1.0, 2.0, 0, 1)));
        Assertions.assertEquals(List.of("c"), ids(store.results("run", "p", false, 500, 2, 1.0, 2.0, 1, 1)));
        Assertions.assertEquals(List.of("b", "c", "d"), ids(store.results("run", null, null, 500, null, null, null, 0, 20)));
        Assertions.assertTrue(store.results("run", null, null, 404, null, null, null, 0, 20).isEmpty());
        Assertions.assertEquals(4, store.results("run", null, null, 0, 20).size());
    }

    @Test void legacyRowsRemainSearchableAfterStartupBackfill() {
        jdbc.update("insert into caller_result(id,run_id,path_id,success,document) values(?,?,?,?,?)",
                "legacy", "run", "p", false, store.encode(result("legacy", "run", 503, 3, 1500000)));
        store.initialize();
        Assertions.assertEquals(List.of("legacy"), ids(store.results("run", null, null, 503, 3, 1.5, 1.5, 0, 20)));
    }

    @Test void rejectsInvalidSearchRanges() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> store.results("run", null, null, null, 0, null, null, 0, 20));
        Assertions.assertThrows(IllegalArgumentException.class, () -> store.results("run", null, null, -1, null, null, null, 0, 20));
        Assertions.assertThrows(IllegalArgumentException.class, () -> store.results("run", null, null, null, null, -1.0, null, 0, 20));
        Assertions.assertThrows(IllegalArgumentException.class, () -> store.results("run", null, null, null, null, 2.0, 1.0, 0, 20));
        Assertions.assertThrows(IllegalArgumentException.class, () -> store.results("run", null, null, null, null, Double.NaN, null, 0, 20));
    }

    private Map<String, Object> result(String id, String run, int status, int worker, long nanos) {
        return Map.of("id", id, "runId", run, "pathId", "p", "success", status == 200,
                "status", status, "workerIndex", worker, "elapsedNanos", nanos);
    }

    private List<Object> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("id")).toList();
    }
}
