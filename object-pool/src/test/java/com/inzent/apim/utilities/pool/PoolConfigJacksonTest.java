package com.inzent.apim.utilities.pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.inzent.apim.utilities.pool.PoolLifecycleTest.*;

class PoolConfigJacksonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void emptyObjectUsesPoolDefaults() throws Exception {
        var config = mapper.readValue("{}", PoolConfig.class);
        assertEquals(new PoolConfig(0, 128, 32, 0, 8), config);
    }

    @Test void omittedFieldsUseDefaultsAndSpecifiedFieldsAreApplied() throws Exception {
        var config = mapper.readValue("""
                {"maxSize":64,"initialSize":2,"initialObjectsPerSub":1}
                """, PoolConfig.class);
        assertEquals(new PoolConfig(2, 64, 32, 1, 8), config);
    }

    @Test void nullFieldsUseDefaults() throws Exception {
        var config = mapper.readValue("""
                {"initialSize":null,"maxSize":null,"maxSubPools":null,
                 "initialObjectsPerSub":null,"maxCachedPerSub":null}
                """, PoolConfig.class);
        assertEquals(new PoolConfig(0, 128, 32, 0, 8), config);
    }

    @Test void explicitZerosDoNotBecomeNonzeroDefaults() throws Exception {
        var config = mapper.readValue("""
                {"initialSize":0,"maxSize":2,"maxSubPools":1,
                 "initialObjectsPerSub":0,"maxCachedPerSub":0}
                """, PoolConfig.class);
        assertEquals(new PoolConfig(0, 2, 1, 0, 0), config);
    }

    @Test void fullySpecifiedConfigActuallyControlsPoolAllocation() throws Exception {
        var config = mapper.readValue("""
                {"initialSize":3,"maxSize":4,"maxSubPools":1,
                 "initialObjectsPerSub":2,"maxCachedPerSub":2}
                """, PoolConfig.class);
        try (var pool = new Pool(config)) {
            pool.start(); var sub = pool.borrowSubPool();
            assertMain(pool, 3, 1, 1); assertSub(sub, 2, 2, 2);
            assertThrows(PoolExhaustedException.class, pool::borrowSubPool);
            sub.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"initialSize\":-1}",
            "{\"maxSize\":0}",
            "{\"maxSubPools\":0}",
            "{\"initialObjectsPerSub\":-1}",
            "{\"initialObjectsPerSub\":9}",
            "{\"maxCachedPerSub\":-1}",
            "{\"initialSize\":129}"
    })
    void invalidJsonConfigUsesSameConstructorValidation(String json) {
        var error = assertThrows(ValueInstantiationException.class,
                () -> mapper.readValue(json, PoolConfig.class));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    @Test void mainIdleLimitDoesNotRestrictSubCacheDefault() throws Exception {
        assertEquals(new PoolConfig(0, 2, 32, 0, 8), mapper.readValue("{\"maxSize\":2}", PoolConfig.class));
    }

    @Test void serializationRoundTripPreservesAllFiveSettings() throws Exception {
        var original = new PoolConfig(3, 17, 5, 2, 7);
        String json = mapper.writeValueAsString(original);
        var tree = mapper.readTree(json);
        assertEquals(5, tree.size());
        assertEquals(3, tree.get("initialSize").intValue());
        assertEquals(17, tree.get("maxSize").intValue());
        assertEquals(5, tree.get("maxSubPools").intValue());
        assertEquals(2, tree.get("initialObjectsPerSub").intValue());
        assertEquals(7, tree.get("maxCachedPerSub").intValue());
        assertEquals(original, mapper.readValue(json, PoolConfig.class));
    }
}
