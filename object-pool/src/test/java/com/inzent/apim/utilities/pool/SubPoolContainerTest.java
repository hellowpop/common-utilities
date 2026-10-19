package com.inzent.apim.utilities.pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import static com.inzent.apim.utilities.pool.PoolLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SubPoolContainerTest {
    @Test void eachSubStartsWithConfiguredInstancesAndReusesItsOwnTargets() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 16, 2, 4, 4))) {
            pool.start(); var a = pool.borrowSubPool(); var b = pool.borrowSubPool();
            assertSub(a, 4, 4, 4); assertSub(b, 4, 4, 4);
            assertMain(pool, 8, 0, 2);
            var aHolders = new ArrayList<ObjectHolder<Resource>>();
            var bHolders = new ArrayList<ObjectHolder<Resource>>();
            var aTargets = new HashSet<Resource>(); var bTargets = new HashSet<Resource>();
            for (int i = 0; i < 4; i++) {
                var ha = a.borrowObjectHolder(); var hb = b.borrowObjectHolder();
                aHolders.add(ha); bHolders.add(hb); aTargets.add(ha.get()); bTargets.add(hb.get());
            }
            assertEquals(4, aTargets.size()); assertEquals(4, bTargets.size());
            assertTrue(aTargets.stream().noneMatch(bTargets::contains));
            assertEquals(8, pool.generated.get());
            for (var h : aHolders) h.close(); for (var h : bHolders) h.close();
            for (int i = 0; i < 4; i++) {
                try (var ha = a.borrowObjectHolder(); var hb = b.borrowObjectHolder()) {
                    assertTrue(aTargets.contains(ha.get())); assertTrue(bTargets.contains(hb.get()));
                }
            }
            assertSub(a, 4, 4, 4); assertSub(b, 4, 4, 4);
            a.close(); b.close(); assertMain(pool, 8, 8, 0);
            assertSub(a, 4, 0, 0); assertSub(b, 4, 0, 0);
        }
    }

    @Test void otherSubCreatesAdditionalTargetWithoutTakingOwnedIdle() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 2, 2, 1, 1))) {
            pool.start(); var a = pool.borrowSubPool(); var b = pool.borrowSubPool();
            var old = a.borrowObjectHolder(); Resource target = old.get(); old.close();
            var activeB = b.borrowObjectHolder();
            ObjectHolder<Resource> extraB = null;
            try {
                extraB = b.borrowObjectHolder();
                assertNotSame(target, extraB.get());
                assertSub(a, 1, 1, 1); assertSub(b, 1, 2, 0);
                try (var again = a.borrowObjectHolder()) {
                    assertSame(target, again.get()); old.close(); assertSame(target, again.get());
                    assertNotSame(activeB.get(), again.get());
                }
            } finally { activeB.close(); if (extraB != null) extraB.close(); a.close(); b.close(); }
        }
    }

    @Test void newSubReceivesFullInitialAllocationFromReturnedMainIdle() throws Exception {
        try (var pool = new Pool(new PoolConfig(4, 6, 2, 3, 3))) {
            pool.start(); var first = pool.borrowSubPool();
            assertSub(first, 3, 3, 3); first.close();
            var next = pool.borrowSubPool();
            assertNotSame(first, next); assertSub(next, 3, 3, 3);
            assertEquals(4, pool.generated.get()); next.close();
        }
    }

    @Test void zeroCacheReturnsObjectsThroughMainForAnotherSub() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 1, 2, 0, 0))) {
            pool.start(); var a = pool.borrowSubPool(); var b = pool.borrowSubPool();
            var ha = a.borrowObjectHolder(); var target = ha.get(); ha.close();
            assertSub(a, 0, 0, 0); assertMain(pool, 1, 1, 2);
            try (var hb = b.borrowObjectHolder()) { assertSame(target, hb.get()); }
            assertSub(b, 0, 0, 0); a.close(); b.close();
        }
    }

    @Test void jacksonInitialCountIsAppliedBeforeSubBorrowReturns() throws Exception {
        var config = new ObjectMapper().readValue("""
                {"maxSize":8,"initialObjectsPerSub":3,"maxCachedPerSub":3}
                """, PoolConfig.class);
        try (var pool = new Pool(config)) {
            pool.start(); var sub = pool.borrowSubPool();
            assertEquals(3, pool.generated.get()); assertSub(sub, 3, 3, 3);
            try (var h = sub.borrowObjectHolder()) { assertSub(sub, 3, 3, 2); }
            assertSub(sub, 3, 3, 3); sub.close();
        }
    }
}
