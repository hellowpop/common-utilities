package com.inzent.apim.utilities.ldap;

import org.junit.jupiter.api.Test;
import javax.naming.*;
import javax.naming.directory.BasicAttributes;
import javax.naming.ldap.LdapContext;
import javax.naming.spi.InitialContextFactory;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LdapConnectionPoolTest {
    static class Fake {
        int closes;
        int reads;
        boolean failRead;
        boolean failClose;
        Object derived;
        CountDownLatch entered;
        CountDownLatch proceed;
        CountDownLatch closed;
        final LdapContext context = (LdapContext) Proxy.newProxyInstance(
                LdapContext.class.getClassLoader(), new Class<?>[]{LdapContext.class}, (p, m, args) -> {
                    switch (m.getName()) {
                        case "close": closes++; if (closed != null) closed.countDown(); if (failClose) throw new NamingException("close failed"); return null;
                        case "getAttributes": reads++; if (failRead) throw new CommunicationException("disconnected"); return new BasicAttributes();
                        case "getNameInNamespace":
                            if (entered != null) { entered.countDown(); if (!proceed.await(3, TimeUnit.SECONDS)) throw new AssertionError("operation timed out"); }
                            return "cn=test";
                        case "getEnvironment": return new Hashtable<>();
                        case "newInstance": return derived;
                        case "extendedOperation": return derived;
                        case "list": return derived;
                        case "equals": return p == args[0];
                        case "hashCode": return System.identityHashCode(p);
                        default: return null;
                    }
                });
    }

    static class Fixture {
        final List<Fake> created = new ArrayList<>();
        final AtomicLong now = new AtomicLong();
        final LdapConnectionPool pool;
        Fixture(int max, int min, long idle) {
            pool = new LdapConnectionPool(new Properties(), max, min, idle, props -> {
                Fake fake = new Fake(); created.add(fake); return fake.context;
            }, now::get);
        }
    }

    @Test void openTestsConnectionAndCloseReturnsItForReuse() throws Exception {
        Fixture f = new Fixture(2, 1, 100);
        try {
            f.pool.open(); f.pool.open();
            assertEquals(1, f.created.size());
            assertEquals(1, f.created.getFirst().reads);
            LdapContext first = f.pool.borrow();
            assertEquals("cn=test", first.getNameInNamespace());
            first.close(); first.close();
            assertEquals(0, f.created.getFirst().closes);
            assertThrows(NamingException.class, first::getNameInNamespace);
            LdapContext second = f.pool.borrow();
            assertNotSame(first, second);
            assertEquals(1, f.created.size());
            second.close();
        } finally { f.pool.dispose(); }
        assertEquals(1, f.created.getFirst().closes);
    }

    @Test void runWithReturnsConnectionEvenWhenConsumerFails() throws Exception {
        Fixture f = new Fixture(1, 0, 100);
        try {
            IllegalStateException error = new IllegalStateException("consumer failed");
            assertSame(error, assertThrows(IllegalStateException.class,
                    () -> f.pool.runWith(ctx -> { throw error; })));
            f.pool.borrow().close();
            assertEquals(1, f.created.size());
            assertThrows(NullPointerException.class, () -> f.pool.runWith(null));
        } finally { f.pool.dispose(); }
    }

    @Test void maxIsEnforcedAndDisposeClosesBorrowedContexts() throws Exception {
        Fixture f = new Fixture(2, 1, 100);
        LdapContext one = f.pool.borrow(), two = f.pool.borrow();
        assertThrows(NamingException.class, f.pool::borrow);
        f.pool.dispose(); f.pool.dispose();
        assertEquals(List.of(1, 1), f.created.stream().map(x -> x.closes).toList());
        assertThrows(NamingException.class, one::getNameInNamespace);
        one.close(); two.close();
        assertThrows(IllegalStateException.class, f.pool::open);
        assertThrows(IllegalStateException.class, f.pool::borrow);
    }

    @Test void idleTimeoutPreservesMinAndNeverEvictsBorrowedConnection() throws Exception {
        Fixture f = new Fixture(3, 1, 10);
        try {
            LdapContext one = f.pool.borrow(), two = f.pool.borrow(), three = f.pool.borrow();
            one.close(); two.close();
            f.now.set(TimeUnit.MILLISECONDS.toNanos(11));
            f.pool.evictIdle();
            assertEquals(2, f.created.stream().mapToInt(x -> x.closes).sum());
            assertEquals("cn=test", three.getNameInNamespace());
            three.close(); f.pool.evictIdle();
            assertEquals(2, f.created.stream().mapToInt(x -> x.closes).sum());
        } finally { f.pool.dispose(); }
    }

    @Test void failedOrModifiedContextsAreDiscarded() throws Exception {
        Fixture f = new Fixture(1, 1, 100);
        try {
            LdapContext first = f.pool.borrow();
            f.created.getFirst().failRead = true;
            assertThrows(CommunicationException.class, () -> first.getAttributes(""));
            first.close();
            assertEquals(1, f.created.getFirst().closes);
            LdapContext second = f.pool.borrow();
            second.addToEnvironment(Context.SECURITY_PRINCIPAL, "other");
            second.close();
            assertEquals(1, f.created.get(1).closes);
            f.pool.borrow().close();
            assertEquals(3, f.created.size());
        } finally { f.pool.dispose(); }
    }

    @Test void failedOpenCleansUpAndCanBeRetried() throws Exception {
        List<Fake> created = new ArrayList<>();
        LdapConnectionPool pool = new LdapConnectionPool(new Properties(), 2, 2, 100, props -> {
            Fake fake = new Fake(); fake.failRead = created.size() == 1;
            created.add(fake); return fake.context;
        }, System::nanoTime);
        try {
            assertThrows(CommunicationException.class, pool::open);
            assertEquals(List.of(1, 1), created.stream().map(x -> x.closes).toList());
            pool.open(); assertEquals(4, created.size());
        } finally { pool.dispose(); }
    }

    @Test void disposeContinuesAfterCloseFailure() throws Exception {
        Fixture f = new Fixture(2, 2, 100);
        f.pool.open(); f.created.getFirst().failClose = true;
        assertThrows(NamingException.class, f.pool::dispose);
        assertEquals(List.of(1, 1), f.created.stream().map(x -> x.closes).toList());
        f.pool.dispose();
    }

    @Test void propertiesIncludeDefaultsAndAreDefensivelyCopied() throws Exception {
        Properties defaults = new Properties(); defaults.setProperty(Context.PROVIDER_URL, "ldap://example");
        Properties props = new Properties(defaults); props.setProperty("ldap.pool.max", "2");
        List<Properties> received = new ArrayList<>();
        LdapConnectionPool pool = new LdapConnectionPool(props, 2, 0, 100, env -> {
            received.add(env); return new Fake().context;
        }, System::nanoTime);
        props.setProperty(Context.PROVIDER_URL, "ldap://changed");
        try {
            pool.borrow().close();
            assertEquals("ldap://example", received.getFirst().getProperty(Context.PROVIDER_URL));
            assertFalse(received.getFirst().containsKey("ldap.pool.max"));
            assertEquals("false", received.getFirst().getProperty("com.sun.jndi.ldap.connect.pool"));
        } finally { pool.dispose(); }
    }

    @Test void rejectsInvalidSettingsBeforeConnecting() {
        assertThrows(NullPointerException.class, () -> new LdapConnectionPool(null));
        assertThrows(IllegalArgumentException.class, () -> new LdapConnectionPool(new Properties(), 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LdapConnectionPool(new Properties(), 1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new LdapConnectionPool(new Properties(), 1, 0, -1));
        Properties p = new Properties(); p.setProperty("ldap.pool.max", "bad");
        assertThrows(IllegalArgumentException.class, () -> new LdapConnectionPool(p));
    }

    @Test void derivedContextsAndEnumerationsCannotEscapeLease() throws Exception {
        Fixture f = new Fixture(1, 0, 0);
        try {
            LdapContext parent = f.pool.borrow();
            Fake child = new Fake(); f.created.getFirst().derived = child.context;
            LdapContext derived = parent.newInstance(null);
            parent.close();
            assertEquals(1, child.closes);
            assertThrows(NamingException.class, derived::getNameInNamespace);
            derived.close(); assertEquals(1, child.closes);

            LdapContext next = f.pool.borrow();
            CountDownLatch closed = new CountDownLatch(1);
            f.created.getLast().derived = new NamingEnumeration<NameClassPair>() {
                public NameClassPair next() { return new NameClassPair("name", "class"); }
                public boolean hasMore() { return true; }
                public void close() { closed.countDown(); }
                public boolean hasMoreElements() { return true; }
                public NameClassPair nextElement() { return next(); }
            };
            NamingEnumeration<NameClassPair> results = next.list("");
            next.close();
            assertEquals(0, closed.getCount());
            assertThrows(NamingException.class, results::hasMore);
            f.pool.borrow().close();
            assertEquals(2, f.created.size());
        } finally { f.pool.dispose(); }
    }

    @Test void independentLeasesRunInParallelAndShutdownWaitsForOperations() throws Exception {
        Fixture f = new Fixture(2, 2, 0);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch entered = new CountDownLatch(2), proceed = new CountDownLatch(1);
        try {
            LdapContext one = f.pool.borrow(), two = f.pool.borrow();
            for (Fake fake : f.created) { fake.entered = entered; fake.proceed = proceed; }
            Future<String> first = executor.submit(one::getNameInNamespace);
            Future<String> second = executor.submit(two::getNameInNamespace);
            assertTrue(entered.await(2, TimeUnit.SECONDS), "independent operations should overlap");
            Future<?> disposal = executor.submit(() -> { f.pool.dispose(); return null; });
            proceed.countDown();
            assertEquals("cn=test", first.get(3, TimeUnit.SECONDS));
            assertEquals("cn=test", second.get(3, TimeUnit.SECONDS));
            disposal.get(3, TimeUnit.SECONDS);
            assertEquals(List.of(1, 1), f.created.stream().map(x -> x.closes).toList());
        } finally { proceed.countDown(); executor.shutdownNow(); f.pool.dispose(); }
    }

    @Test void reaperClosesIdleConnectionWithoutAnotherBorrow() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        LdapConnectionPool pool = new LdapConnectionPool(new Properties(), 1, 0, 20, props -> {
            Fake fake = new Fake(); fake.closed = closed; return fake.context;
        }, System::nanoTime);
        try { pool.borrow().close(); assertTrue(closed.await(3, TimeUnit.SECONDS)); }
        finally { pool.dispose(); }
    }

    @Test void zeroTimeoutDisablesEviction() throws Exception {
        Fixture f = new Fixture(1, 0, 0);
        try {
            f.pool.borrow().close(); f.now.set(Long.MAX_VALUE); f.pool.evictIdle();
            f.pool.borrow().close(); assertEquals(1, f.created.size());
        } finally { f.pool.dispose(); }
    }

    @Test void contextsInsideSearchResultsAreTrackedAndClosed() throws Exception {
        Fixture f = new Fixture(1, 0, 0);
        try {
            LdapContext parent = f.pool.borrow();
            Fake child = new Fake();
            javax.naming.directory.SearchResult result = new javax.naming.directory.SearchResult(
                    "cn=child", child.context, new BasicAttributes());
            f.created.getFirst().derived = new NamingEnumeration<Binding>() {
                public Binding next() { return result; }
                public boolean hasMore() { return true; }
                public void close() { }
                public boolean hasMoreElements() { return true; }
                public Binding nextElement() { return next(); }
            };
            Object returned = parent.list("").next();
            assertSame(result, returned);
            LdapContext nested = (LdapContext) ((Binding) returned).getObject();
            parent.close();
            assertEquals(1, child.closes);
            assertThrows(NamingException.class, nested::getNameInNamespace);
        } finally { f.pool.dispose(); }
    }

    @Test void consumerFailureKeepsCleanupFailureSuppressed() throws Exception {
        Fixture f = new Fixture(1, 0, 0);
        try {
            RuntimeException original = new RuntimeException("consumer");
            RuntimeException result = assertThrows(RuntimeException.class, () -> f.pool.runWith(ctx -> {
                f.created.getFirst().failClose = true;
                try { ctx.setRequestControls(null); } catch (NamingException ex) { throw new AssertionError(ex); }
                throw original;
            }));
            assertSame(original, result);
            assertEquals(1, result.getSuppressed().length);
            assertInstanceOf(NamingException.class, result.getSuppressed()[0]);
        } finally { f.pool.dispose(); }
    }

    @Test void failedExplicitEnumerationCloseDiscardsParent() throws Exception {
        Fixture f = new Fixture(1, 0, 0);
        try {
            LdapContext parent = f.pool.borrow();
            f.created.getFirst().derived = new NamingEnumeration<NameClassPair>() {
                public NameClassPair next() { return null; }
                public boolean hasMore() { return false; }
                public void close() throws NamingException { throw new NamingException("enumeration close failed"); }
                public boolean hasMoreElements() { return false; }
                public NameClassPair nextElement() { return null; }
            };
            NamingEnumeration<NameClassPair> enumeration = parent.list("");
            assertThrows(NamingException.class, enumeration::close);
            parent.close();
            assertEquals(1, f.created.getFirst().closes);
        } finally { f.pool.dispose(); }
    }

    public static class TestContextFactory implements InitialContextFactory {
        static final List<Fake> contexts = new ArrayList<>();
        @Override public Context getInitialContext(Hashtable<?, ?> environment) {
            Fake fake = new Fake(); contexts.add(fake); return fake.context;
        }
    }

    @Test void publicPropertiesConstructorUsesJndiAndHonorsPoolSettings() throws Exception {
        TestContextFactory.contexts.clear();
        Properties defaults = new Properties(); defaults.setProperty(LdapConnectionPool.MAX_PROPERTY, "1");
        Properties props = new Properties(defaults);
        props.setProperty(Context.INITIAL_CONTEXT_FACTORY, TestContextFactory.class.getName());
        props.setProperty(LdapConnectionPool.MIN_PROPERTY, "0");
        props.setProperty(LdapConnectionPool.IDLE_TIMEOUT_PROPERTY, "0");
        try (LdapConnectionPool pool = new LdapConnectionPool(props)) {
            LdapContext context = pool.borrow();
            assertThrows(NamingException.class, pool::borrow);
            context.close(); pool.borrow().close();
            assertEquals(1, TestContextFactory.contexts.size());
        }
        assertEquals(1, TestContextFactory.contexts.getFirst().closes);
    }
}
