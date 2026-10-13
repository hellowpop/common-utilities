package com.inzent.apim.utilities.ldap;

import javax.naming.NamingException;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Thread-safe pool with independent close handles for each borrow. */
public final class LdapConnectionPool implements AutoCloseable {
    public static final String MAX_PROPERTY = "ldap.pool.max";
    public static final String MIN_PROPERTY = "ldap.pool.min";
    public static final String IDLE_TIMEOUT_PROPERTY = "ldap.pool.idleTimeoutMillis";
    public static final int DEFAULT_MAX = 8;
    public static final int DEFAULT_MIN = 1;
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 300_000;

    private final Properties environment = new Properties();
    private final int max, min;
    private final long idleTimeoutNanos;
    private final LdapContextFactory factory;
    private final LongSupplier clock;
    private final Set<LdapPoolEntry> entries = new LinkedHashSet<>();
    private ScheduledExecutorService reaper;
    private boolean opened, disposed;

    /** Pool settings in Properties are optional. Construction does not connect. */
    public LdapConnectionPool(Properties properties) {
        this(properties, integerSetting(properties, MAX_PROPERTY, DEFAULT_MAX),
                integerSetting(properties, MIN_PROPERTY, DEFAULT_MIN),
                setting(properties, IDLE_TIMEOUT_PROPERTY, DEFAULT_IDLE_TIMEOUT_MILLIS));
    }

    /** Explicit settings override pool properties. Timeout is milliseconds; zero disables eviction. */
    public LdapConnectionPool(Properties properties, int max, int min, long idleTimeoutMillis) {
        this(properties, max, min, idleTimeoutMillis,
                env -> new InitialLdapContext(env, null), System::nanoTime);
    }

    LdapConnectionPool(Properties properties, int max, int min, long idleTimeoutMillis,
                       LdapContextFactory factory, LongSupplier clock) {
        Objects.requireNonNull(properties, "properties");
        if (max < 1 || min < 0 || min > max || idleTimeoutMillis < 0
                || idleTimeoutMillis > Long.MAX_VALUE / 1_000_000)
            throw new IllegalArgumentException("Require max >= 1, 0 <= min <= max and a valid nonnegative timeout");
        this.max = max;
        this.min = min;
        this.idleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis);
        this.factory = Objects.requireNonNull(factory, "factory");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (String name : properties.stringPropertyNames()) environment.setProperty(name, properties.getProperty(name));
        environment.putAll(properties);
        environment.remove(MAX_PROPERTY);
        environment.remove(MIN_PROPERTY);
        environment.remove(IDLE_TIMEOUT_PROPERTY);
        // Physical close must not return connections to the provider's separate pool.
        environment.setProperty("com.sun.jndi.ldap.connect.pool", "false");
    }

    private static int integerSetting(Properties properties, String key, int fallback) {
        long value = setting(properties, key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Setting out of range: " + key);
        return (int) value;
    }

    private static long setting(Properties properties, String key, long fallback) {
        Object value = Objects.requireNonNull(properties, "properties").get(key);
        if (value == null) value = properties.getProperty(key);
        if (value == null) return fallback;
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid setting: " + key, ex); }
    }

    /** Idempotent startup. Reads the configured base to verify each initial connection. */
    public synchronized void open() throws NamingException {
        if (disposed) throw new IllegalStateException("LDAP connection pool is disposed");
        if (opened) return;
        try {
            while (entries.size() < Math.max(1, min)) entries.add(createEntry());
            if (idleTimeoutNanos > 0) {
                reaper = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "ldap-pool-idle-reaper");
                    thread.setDaemon(true);
                    return thread;
                });
                long period = Math.max(1, Math.min(1_000, TimeUnit.NANOSECONDS.toMillis(idleTimeoutNanos)));
                reaper.scheduleWithFixedDelay(() -> {
                    try { evictIdle(); }
                    catch (NamingException | RuntimeException ex) {
                        System.getLogger(LdapConnectionPool.class.getName()).log(
                                System.Logger.Level.WARNING, "LDAP idle connection cleanup failed");
                    }
                }, period, period, TimeUnit.MILLISECONDS);
            }
            opened = true;
        } catch (Throwable failure) {
            if (reaper != null) { reaper.shutdownNow(); reaper = null; }
            List<LdapPoolEntry> failed = new ArrayList<>(entries);
            entries.clear();
            for (LdapPoolEntry entry : failed) failure = cleanup(failure, entry::destroy);
            throwFailure(failure);
        }
    }

    private LdapPoolEntry createEntry() throws NamingException {
        LdapContext context = Objects.requireNonNull(factory.create((Properties) environment.clone()), "context");
        try {
            context.getAttributes("", new String[]{"1.1"});
            return new LdapPoolEntry(context, clock.getAsLong());
        } catch (Throwable failure) {
            throwFailure(cleanup(failure, context::close));
            throw new AssertionError("unreachable");
        }
    }

    /** Lazily opens the pool. Exhaustion fails immediately. close() returns the lease. */
    public synchronized LdapContext borrow() throws NamingException {
        open();
        evictIdle();
        while (entries.size() < min) entries.add(createEntry());
        LdapPoolEntry entry = entries.stream().filter(e -> e.lease == null).findFirst().orElse(null);
        if (entry == null) {
            if (entries.size() == max) throw new NamingException("LDAP connection pool exhausted (max=" + max + ")");
            entry = createEntry();
            entries.add(entry);
        }
        LdapContextLease lease = new LdapContextLease(this, entry);
        LdapContext proxy = (LdapContext) Proxy.newProxyInstance(LdapContext.class.getClassLoader(),
                new Class<?>[]{LdapContext.class}, lease);
        entry.lease = lease;
        return proxy;
    }

    /** Returns the lease even when the consumer throws, preserving its exception. */
    public void runWith(Consumer<LdapContext> consumer) throws NamingException {
        Objects.requireNonNull(consumer, "consumer");
        LdapContext context = borrow();
        Throwable failure = null;
        try { consumer.accept(context); }
        catch (Throwable ex) { failure = ex; }
        throwFailure(cleanup(failure, context::close));
    }

    synchronized void release(LdapPoolEntry entry) throws NamingException {
        entry.lease = null;
        if (disposed || entry.dirty) {
            entries.remove(entry);
            entry.destroy();
        } else entry.idleSince = clock.getAsLong();
    }

    synchronized void evictIdle() throws NamingException {
        if (disposed || idleTimeoutNanos == 0) return;
        long now = clock.getAsLong();
        Throwable failure = null;
        var iterator = entries.iterator();
        while (iterator.hasNext() && entries.size() > min) {
            LdapPoolEntry entry = iterator.next();
            if (entry.lease == null && now - entry.idleSince >= idleTimeoutNanos) {
                iterator.remove();
                failure = cleanup(failure, entry::destroy);
            }
        }
        throwFailure(failure);
    }

    /** Terminal shutdown. Attempts to close every idle and borrowed connection. */
    public void dispose() throws NamingException {
        List<LdapPoolEntry> closing;
        synchronized (this) {
            if (disposed) return;
            disposed = true;
            if (reaper != null) { reaper.shutdownNow(); reaper = null; }
            closing = new ArrayList<>(entries);
            entries.clear();
            environment.clear();
        }
        // Release the pool monitor before waiting for in-flight LDAP operations.
        Throwable failure = null;
        for (LdapPoolEntry entry : closing) failure = cleanup(failure, entry::destroy);
        throwFailure(failure);
    }

    @Override public void close() throws NamingException { dispose(); }

    static Throwable cleanup(Throwable failure, LdapCleanupAction action) {
        try { action.close(); }
        catch (Throwable ex) {
            if (failure == null) return ex;
            if (ex != failure) failure.addSuppressed(ex);
        }
        return failure;
    }

    static void throwFailure(Throwable failure) throws NamingException {
        if (failure == null) return;
        if (failure instanceof NamingException ex) throw ex;
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
        NamingException ex = new NamingException("LDAP resource cleanup failed");
        ex.setRootCause(failure);
        throw ex;
    }

    static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "PooledLdapResource@" + Integer.toHexString(System.identityHashCode(proxy));
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
