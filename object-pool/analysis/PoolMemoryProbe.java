package com.inzent.apim.utilities.pool.analysis;

import com.inzent.apim.utilities.pool.*;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.*;

/** Deterministic backing-storage measurement. Requires java.util/java.lang to be opened. */
public final class PoolMemoryProbe {
    private static final class Pool extends MainPool<Object> {
        Pool(PoolConfig config) { super(config); }
        @Override protected Object generateTargetObject() { return new Object(); }
        @Override protected void destroyTargetObject(Object value) {}
    }
    private static Object read(Object object, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static int mapCapacity(Object map) throws Exception {
        Class<?> owner = map instanceof IdentityHashMap<?, ?> ? IdentityHashMap.class : HashMap.class;
        Object[] table = (Object[]) read(map, owner, "table"); return table == null ? 0 : table.length;
    }
    private static int dequeCapacity(Object deque) throws Exception {
        return ((Object[]) read(deque, ArrayDeque.class, "elements")).length;
    }
    private static int[] storage(MainPool<?> main, SubPool<?> sub) throws Exception {
        Object container = read(sub, SubPool.class, "container");
        return new int[]{mapCapacity(read(main, MainPool.class, "entries")),
                mapCapacity(read(main, MainPool.class, "targets")),
                mapCapacity(read(main, MainPool.class, "subs")),
                dequeCapacity(read(main, MainPool.class, "idle")),
                mapCapacity(read(container, container.getClass(), "managed")),
                dequeCapacity(read(container, container.getClass(), "idle"))};
    }
    private static void print(String label, int[] sizes) {
        System.out.println(label + " [Main.entries, Main.targets, Main.subs, Main.idle, Sub.managed, Sub.idle]="
                + Arrays.toString(sizes));
    }
    public static void main(String[] args) throws Exception {
        boolean verify = args.length == 1 && "--check-compaction".equals(args[0]);
        try (var pool = new Pool(new PoolConfig(0, 2, 1, 0, 0))) {
            pool.start(); var sub = pool.borrowSubPool();
            var holders = new ArrayList<ObjectHolder<Object>>();
            for (int i = 0; i < 4096; i++) holders.add(sub.borrowObjectHolder());
            print("BURST", storage(pool, sub));
            for (var h : holders) h.close();
            int[] returned = storage(pool, sub); print("RETURNED", returned);
            if (verify && (returned[0] > 128 || returned[1] > 128 || returned[4] > 128))
                throw new AssertionError("burst storage retained");
            sub.close(); pool.close(); print("CLOSED", storage(pool, sub));
        }
        try (var pool = new Pool(new PoolConfig(1024, 1024, 1, 1024, 1024))) {
            pool.start(); var sub = pool.borrowSubPool(); print("LARGE_IDLE", storage(pool, sub));
            sub.close(); pool.close(); int[] closed = storage(pool, sub); print("LARGE_CLOSED", closed);
            if (verify) for (int size : closed) if (size > 16) throw new AssertionError("closed storage retained");
        }
        try (var pool = new Pool(new PoolConfig(0, 1, 1, 0, 0))) {
            pool.start(); Object guard = read(pool, MainPool.class, "inHook");
            Object locals = read(Thread.currentThread(), Thread.class, "threadLocals");
            boolean present = false;
            if (locals != null) {
                Object[] table = (Object[]) read(locals, locals.getClass(), "table");
                for (Object entry : table) if (entry != null && ((Reference<?>) entry).get() == guard) present = true;
            }
            System.out.println("IDLE_THREAD_LOCAL_PRESENT=" + present);
            if (verify && present) throw new AssertionError("empty guard entry retained");
        }
    }
}
