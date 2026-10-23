package com.inzent.apim.utilities.pool;

import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import static com.inzent.apim.utilities.pool.PoolLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PoolMemorySafetyTest {
    // IDE and in-process Maven runs need not open JDK modules in their parent JVM.
    private static boolean runInInspectionJvm(String method) throws Exception {
        Module base = Object.class.getModule(); Module caller = PoolMemorySafetyTest.class.getModule();
        if (base.isOpen("java.util", caller) && base.isOpen("java.lang", caller)) return false;
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null && PoolMemorySafetyTest.class.getClassLoader() instanceof URLClassLoader loader) {
            var paths = new ArrayList<String>();
            for (var url : loader.getURLs()) if ("file".equals(url.getProtocol())) paths.add(Path.of(url.toURI()).toString());
            classPath = String.join(File.pathSeparator, paths);
        }
        if (classPath == null || classPath.isEmpty()) classPath = System.getProperty("java.class.path");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path output = Files.createTempFile("object-pool-inspection-", ".log");
        Process child = null;
        try {
            child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "--add-opens", "java.base/java.util=ALL-UNNAMED",
                    "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-cp", classPath,
                    PoolMemorySafetyTest.class.getName(), method)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "inspection JVM timed out");
            assertEquals(0, child.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
            return true;
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            Files.deleteIfExists(output);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a test method");
        var method = PoolMemorySafetyTest.class.getDeclaredMethod(args[0]);
        if (!method.isAnnotationPresent(Test.class)) throw new IllegalArgumentException("Not a test method");
        try { method.invoke(new PoolMemorySafetyTest()); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    // Inspect allocated backing arrays, rather than making assertions about GC timing.
    private static Object read(Object object, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static int mapCapacity(Object map) throws Exception {
        Class<?> owner = map instanceof IdentityHashMap<?, ?> ? IdentityHashMap.class : HashMap.class;
        Object[] table = (Object[]) read(map, owner, "table");
        return table == null ? 0 : table.length;
    }
    private static int dequeCapacity(Object deque) throws Exception {
        return ((Object[]) read(deque, ArrayDeque.class, "elements")).length;
    }
    private static Object mainField(MainPool<?> pool, String name) throws Exception {
        return read(pool, MainPool.class, name);
    }
    private static Object containerField(SubPool<?> sub, String name) throws Exception {
        return read(sub.container, SubPoolContainer.class, name);
    }

    @Test void returnedBurstReleasesPeakSizedGlobalAndSubMaps() throws Exception {
        if (runInInspectionJvm("returnedBurstReleasesPeakSizedGlobalAndSubMaps")) return;
        try (var pool = new Pool(new PoolConfig(0, 2, 1, 0, 0))) {
            pool.start(); var sub = pool.borrowSubPool();
            var holders = new ArrayList<ObjectHolder<Resource>>();
            for (int i = 0; i < 4096; i++) holders.add(sub.borrowObjectHolder());
            assertTrue(mapCapacity(mainField(pool, "entries")) >= 4096);
            for (var holder : holders) holder.close();
            assertMain(pool, 2, 2, 1); assertSub(sub, 0, 0, 0);
            assertTrue(mapCapacity(mainField(pool, "entries")) <= 128, "Main entry backing array retains burst");
            assertTrue(mapCapacity(mainField(pool, "targets")) <= 128, "Main identity backing array retains burst");
            assertTrue(mapCapacity(containerField(sub, "managed")) <= 128, "Sub managed backing array retains burst");
            sub.close();
        }
    }

    @Test void closedMainAndSubReleaseEmptyBackingArrays() throws Exception {
        if (runInInspectionJvm("closedMainAndSubReleaseEmptyBackingArrays")) return;
        var pool = new Pool(new PoolConfig(1024, 1024, 1, 1024, 1024));
        pool.start(); var sub = pool.borrowSubPool();
        assertTrue(dequeCapacity(containerField(sub, "idle")) >= 1024);
        sub.close(); pool.close();
        assertTrue(pool.isTerminated()); assertSub(sub, 1024, 0, 0);
        assertTrue(mapCapacity(containerField(sub, "managed")) <= 16, "closed Sub retains table");
        assertTrue(dequeCapacity(containerField(sub, "idle")) <= 16, "closed Sub retains idle array");
        assertTrue(mapCapacity(mainField(pool, "entries")) <= 16, "closed Main retains table");
        assertTrue(mapCapacity(mainField(pool, "targets")) <= 16, "closed Main retains identity table");
        assertTrue(mapCapacity(mainField(pool, "subs")) <= 16, "closed Main retains Sub table");
        assertTrue(dequeCapacity(mainField(pool, "idle")) <= 16, "closed Main retains idle array");
    }

    @Test void lateReturnsReleaseClosedSubRegistryHighWaterStorage() throws Exception {
        if (runInInspectionJvm("lateReturnsReleaseClosedSubRegistryHighWaterStorage")) return;
        try (var pool = new Pool(new PoolConfig(0, 2, 1, 0, 0));
             var logging = new PoolDiagnosticsTest.Logging(record -> {})) {
            pool.start(); var holders = new ArrayList<ObjectHolder<Resource>>();
            var subs = new ArrayList<SubPool<Resource>>();
            for (int i = 0; i < 512; i++) {
                var sub = pool.borrowSubPool(); subs.add(sub);
                holders.add(sub.borrowObjectHolder()); sub.close();
            }
            assertEquals(512, pool.stats().closedSubPoolCount());
            assertTrue(mapCapacity(mainField(pool, "subs")) >= 512);
            for (var h : holders) h.close();
            assertMain(pool, 2, 2, 0); assertEquals(0, pool.stats().closedSubPoolCount());
            assertTrue(mapCapacity(mainField(pool, "subs")) <= 128, "closed Sub registry retains high-water table");
            for (var sub : subs) assertSub(sub, 0, 0, 0);
        }
    }

    @Test void emptySubIdleStorageShrinksWhenMostEntriesAreBorrowed() throws Exception {
        if (runInInspectionJvm("emptySubIdleStorageShrinksWhenMostEntriesAreBorrowed")) return;
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 1024, 1024))) {
            pool.start(); var sub = pool.borrowSubPool();
            assertTrue(dequeCapacity(containerField(sub, "idle")) >= 1024);
            var holders = new ArrayList<ObjectHolder<Resource>>();
            for (int i = 0; i < 1024; i++) holders.add(sub.borrowObjectHolder());
            assertSub(sub, 1024, 1024, 0);
            assertTrue(dequeCapacity(containerField(sub, "idle")) <= 128, "drained idle queue retains backing array");
            for (var h : holders) h.close(); sub.close();
        }
    }

    @Test void ordinaryEntryCallsDoNotLeaveEmptyThreadLocalEntries() throws Exception {
        if (runInInspectionJvm("ordinaryEntryCallsDoNotLeaveEmptyThreadLocalEntries")) return;
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 0, 0))) {
            pool.start(); var sub = pool.borrowSubPool(); sub.close();
            Object key = mainField(pool, "inHook");
            Object locals = read(Thread.currentThread(), Thread.class, "threadLocals");
            if (locals != null) {
                Object[] table = (Object[]) read(locals, locals.getClass(), "table");
                for (Object entry : table)
                    if (entry != null) assertNotSame(key, ((Reference<?>) entry).get(), "idle pool guard remains in worker ThreadLocalMap");
            }
        }
    }

    @Test void nullFailuresClearTransitiveContainerReferences() throws Exception {
        var pool = new Pool(new PoolConfig(0, 1, 1, 4, 4)) {
            @Override protected void destroyTargetObject(Resource resource) throws Exception {
                super.destroyTargetObject(resource); throw new NullPointerException("destroy NPE");
            }
        };
        pool.start(); var sub = pool.borrowSubPool(); var holder = sub.borrowObjectHolder();
        Resource target = holder.get();
        assertTrue(reaches(sub, pool)); assertTrue(reaches(sub, target));
        assertTrue(reaches(holder, target));
        try (var logging = new PoolDiagnosticsTest.Logging(record -> { throw new NullPointerException("logger NPE"); })) {
            var error = assertThrows(NullPointerException.class, sub::close);
            assertEquals(2, error.getSuppressed().length);
            assertSub(sub, 4, 1, 0);
        }
        assertThrows(NullPointerException.class, pool::close);
        assertThrows(NullPointerException.class, holder::close);
        assertTrue(pool.isTerminated()); assertMain(pool, 0, 0, 0); assertSub(sub, 4, 0, 0);
        assertFalse(reaches(sub, pool)); assertFalse(reaches(sub, target));
        assertFalse(reaches(holder, pool)); assertFalse(reaches(holder, target));
        assertEquals(4, pool.destroyed.get()); holder.close(); sub.close(); pool.close();
    }

    @Test void concurrentGetStatsAndFinalReturnNeverExposeNull() throws Exception {
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 0, 0));
             var workers = new PoolConcurrencyTest.Workers()) {
            pool.start(); var sub = pool.borrowSubPool(); var holder = sub.borrowObjectHolder();
            var gate = new CountDownLatch(1);
            var reader = workers.executor.submit(() -> {
                PoolConcurrencyTest.await(gate);
                for (int i = 0; i < 2000; i++) {
                    assertNotNull(sub.stats());
                    try { assertNotNull(holder.get()); } catch (IllegalStateException expected) { }
                }
                return null;
            });
            gate.countDown(); pool.close(); holder.close(); sub.close();
            reader.get(5, TimeUnit.SECONDS); assertTrue(pool.isTerminated());
        }
    }

    // Walk real container maps/deques and own-package fields, excluding static and weak references.
    private static boolean reaches(Object root, Object target) throws Exception {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var pending = new ArrayDeque<Object>(); pending.add(root);
        while (!pending.isEmpty()) {
            Object value = pending.removeFirst();
            if (value == target) return true;
            if (!seen.add(value) || value instanceof Reference<?>) continue;
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, item) -> { if (key != null) pending.add(key); if (item != null) pending.add(item); });
            } else if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) if (item != null) pending.add(item);
            } else if (value.getClass().getPackageName().startsWith("com.inzent.apim.utilities")) {
                for (Class<?> type = value.getClass(); type != null && type.getPackageName().startsWith("com.inzent.apim.utilities"); type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                            field.setAccessible(true); Object item = field.get(value); if (item != null) pending.add(item);
                        }
                    }
                }
            }
        }
        return false;
    }
}
