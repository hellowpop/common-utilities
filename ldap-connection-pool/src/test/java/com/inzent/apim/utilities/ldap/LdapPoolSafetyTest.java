package com.inzent.apim.utilities.ldap;

import org.junit.jupiter.api.Test;
import javax.naming.*;
import javax.naming.ldap.LdapContext;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LdapPoolSafetyTest {
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object serializedCopy(Object value) throws Exception {
        var buffer = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.ObjectOutputStream(buffer)) { output.writeObject(value); }
        try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray()))) {
            return input.readObject();
        }
    }

    private static NamingEnumeration<NameClassPair> enumeration(AtomicInteger closes) {
        return new NamingEnumeration<>() {
            public NameClassPair next() { return new NameClassPair("name", "type"); }
            public boolean hasMore() { return false; }
            public void close() { closes.incrementAndGet(); }
            public boolean hasMoreElements() { return false; }
            public NameClassPair nextElement() { return next(); }
        };
    }

    @Test void repeatedSearchCloseDoesNotAccumulateHandlers() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            Object lease = Proxy.getInvocationHandler(root);
            for (int i = 0; i < 500; i++) {
                f.created.get(0).derived = enumeration(new AtomicInteger());
                root.list("").close();
            }
            assertEquals(0, ((Collection<?>) field(lease, "resources")).size());
            root.close();
            assertNull(field(lease, "resources"));
            assertNull(field(lease, "entry"));
            assertNull(field(lease, "owner"));
        } finally { f.pool.dispose(); }
    }

    @Test void exhaustedEnumerationReleasesProviderAndTrackingImmediately() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            AtomicInteger closes = new AtomicInteger();
            f.created.get(0).derived = enumeration(closes);
            NamingEnumeration<NameClassPair> enumeration = root.list("");
            assertFalse(enumeration.hasMore());
            assertEquals(1, closes.get());
            Object handler = Proxy.getInvocationHandler(enumeration);
            assertNull(field(handler, "target"));
            assertNull(field(handler, "lease"));
            assertEquals(0, ((Collection<?>) field(Proxy.getInvocationHandler(root), "resources")).size());
            assertFalse(enumeration.hasMoreElements());
            assertThrows(java.util.NoSuchElementException.class, enumeration::nextElement);
            root.close(); assertEquals(1, closes.get());
        } finally { f.pool.dispose(); }
    }

    @Test void duplicateChildContextUsesOneHandleAndOnePhysicalClose() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            var child = new LdapConnectionPoolTest.Fake(); f.created.get(0).derived = child.context;
            LdapContext one = root.newInstance(null), two = root.newInstance(null);
            assertSame(one, two);
            one.close(); two.close(); root.close();
            assertEquals(1, child.closes);
        } finally { f.pool.dispose(); }
    }

    @Test void iteratorCannotBypassLeaseClose() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            f.created.get(0).derived = enumeration(new AtomicInteger());
            Iterator<NameClassPair> iterator = root.list("").asIterator();
            root.close();
            assertThrows(IllegalStateException.class, iterator::hasNext);
            assertThrows(IllegalStateException.class, iterator::next);
        } finally { f.pool.dispose(); }
    }

    @Test void nullInputsFailAtBoundaryAndNullProviderResultsRemainAllowed() throws Exception {
        assertThrows(NullPointerException.class, () -> new LdapConnectionPool(new Properties(), 1, 0, 0, null, System::nanoTime));
        assertThrows(NullPointerException.class, () -> new LdapConnectionPool(new Properties(), 1, 0, 0, p -> null, null));
        assertThrows(NullPointerException.class, () -> new LdapPoolEntry(null, 0));
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            LdapPoolEntry entry = ((LdapContextLease) Proxy.getInvocationHandler(root)).entry;
            assertThrows(NullPointerException.class, () -> new LdapContextLease(null, entry));
            assertThrows(NullPointerException.class, () -> new LdapContextLease(f.pool, null));
            assertThrows(NullPointerException.class, () -> new LdapContextResource(null, new Object()));
            assertThrows(NullPointerException.class, () -> new LdapContextResource((LdapContextLease) Proxy.getInvocationHandler(root), null));
            assertNull(root.getRequestControls());
            assertNull(root.lookup("optional"));
            root.setRequestControls(null); root.reconnect(null); root.close();
        } finally { f.pool.dispose(); }
    }

    @Test void providerNpeDiscardsConnectionAndPreservesOriginalException() throws Exception {
        NullPointerException failure = new NullPointerException("provider failure");
        AtomicInteger closes = new AtomicInteger();
        LdapContext raw = (LdapContext) Proxy.newProxyInstance(LdapContext.class.getClassLoader(),
                new Class<?>[]{LdapContext.class}, (p, m, a) -> {
                    if (m.getName().equals("getNameInNamespace")) throw failure;
                    if (m.getName().equals("close")) closes.incrementAndGet();
                    return null;
                });
        try (LdapConnectionPool pool = new LdapConnectionPool(new Properties(), 1, 0, 0, p -> raw, System::nanoTime)) {
            LdapContext root = pool.borrow();
            assertSame(failure, assertThrows(NullPointerException.class, root::getNameInNamespace));
            root.close(); assertEquals(1, closes.get());
        }
    }

    @Test void disposeClearsAllReferencesEvenWhenChildCloseThrowsError() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        AssertionError failure = new AssertionError("close failure");
        try {
            LdapContext root = f.pool.borrow();
            f.created.get(0).derived = new NamingEnumeration<NameClassPair>() {
                public NameClassPair next() { return null; }
                public boolean hasMore() { return false; }
                public void close() { throw failure; }
                public boolean hasMoreElements() { return false; }
                public NameClassPair nextElement() { return null; }
            };
            NamingEnumeration<NameClassPair> child = root.list("");
            Object rootHandler = Proxy.getInvocationHandler(root), childHandler = Proxy.getInvocationHandler(child);
            assertSame(failure, assertThrows(AssertionError.class, f.pool::dispose));
            assertEquals(1, f.created.get(0).closes);
            assertNull(field(rootHandler, "entry")); assertNull(field(rootHandler, "owner"));
            assertNull(field(rootHandler, "resources"));
            assertNull(field(childHandler, "target")); assertNull(field(childHandler, "lease"));
            assertTrue(((Properties) field(f.pool, "environment")).isEmpty());
            assertNull(field(f.pool, "reaper"));
            root.close(); child.close();
        } finally { f.pool.dispose(); }
    }

    @Test void returningRootAsChildDoesNotDoubleCloseIt() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            f.created.get(0).derived = f.created.get(0).context;
            root.newInstance(null);
            root.close();
            assertEquals(1, f.created.get(0).closes);
        } finally { f.pool.dispose(); }
    }

    @Test void cleanupReentryCannotSkipOtherChildrenOrReleaseTwice() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            var one = new LdapConnectionPoolTest.Fake();
            f.created.get(0).derived = one.context; root.newInstance(null);
            f.created.get(0).derived = new NamingEnumeration<NameClassPair>() {
                public NameClassPair next() { return null; }
                public boolean hasMore() { return true; }
                public void close() throws NamingException { root.close(); }
                public boolean hasMoreElements() { return true; }
                public NameClassPair nextElement() { return null; }
            };
            root.list(""); root.close();
            assertEquals(1, one.closes);
            assertEquals(1, f.created.get(0).closes);
        } finally { f.pool.dispose(); }
    }

    @Test void attributesKeepValuesButDetachProviderReferencesAndSchemaAfterClose() throws Exception {
        var schema = new LdapConnectionPoolTest.Fake();
        AtomicInteger definitions = new AtomicInteger();
        Attribute provider = new BasicAttribute("cn", "name") {
            @Override public DirContext getAttributeDefinition() {
                definitions.incrementAndGet(); return schema.context;
            }
        };
        Attributes rawAttributes = new BasicAttributes(); rawAttributes.put(provider);
        AtomicInteger closes = new AtomicInteger();
        LdapContext raw = (LdapContext) Proxy.newProxyInstance(LdapContext.class.getClassLoader(),
                new Class<?>[]{LdapContext.class}, (p, m, a) -> {
                    if (m.getName().equals("getAttributes")) return rawAttributes;
                    if (m.getName().equals("close")) closes.incrementAndGet();
                    return null;
                });
        try (LdapConnectionPool pool = new LdapConnectionPool(new Properties(), 1, 0, 0, p -> raw, System::nanoTime)) {
            LdapContext root = pool.borrow();
            Attributes detached = root.getAttributes("");
            Attribute attribute = detached.get("cn"), clone = (Attribute) attribute.clone();
            assertNotSame(provider, attribute);
            Attribute serialized = (Attribute) serializedCopy(attribute);
            assertEquals("name", serialized.get());
            assertThrows(NamingException.class, serialized::getAttributeDefinition);
            DirContext derived = attribute.getAttributeDefinition();
            assertNotSame(schema.context, derived);
            assertEquals(1, definitions.get());
            root.close();
            assertEquals("name", attribute.get());
            assertEquals("name", clone.get());
            assertNull(field(attribute, "source")); assertNull(field(attribute, "lease"));
            assertNull(field(clone, "source")); assertNull(field(clone, "lease"));
            assertThrows(NamingException.class, attribute::getAttributeDefinition);
            assertThrows(NamingException.class, clone::getAttributeDefinition);
            assertThrows(NamingException.class, derived::getNameInNamespace);
            assertEquals(1, schema.closes);
            assertEquals(1, closes.get());
        }
    }

    @Test void closeDisposeAndUseRaceNeverDereferencesClearedState() throws Exception {
        var executor = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < 40; i++) {
                var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
                LdapContext root = f.pool.borrow();
                CountDownLatch start = new CountDownLatch(1);
                var call = executor.submit(() -> {
                    start.await();
                    try { root.getNameInNamespace(); } catch (NamingException expected) { }
                    return null;
                });
                var returned = executor.submit(() -> { start.await(); root.close(); return null; });
                var disposed = executor.submit(() -> { start.await(); f.pool.dispose(); return null; });
                start.countDown();
                try {
                    call.get(3, TimeUnit.SECONDS);
                    returned.get(3, TimeUnit.SECONDS);
                    disposed.get(3, TimeUnit.SECONDS);
                    assertEquals(1, f.created.get(0).closes);
                } finally { f.pool.dispose(); }
            }
        } finally { executor.shutdownNow(); }
    }

    @Test void exhaustedLegacyEnumerationAlsoReleasesReferences() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            AtomicInteger closes = new AtomicInteger();
            f.created.get(0).derived = enumeration(closes);
            NamingEnumeration<NameClassPair> results = root.list("");
            assertFalse(results.hasMoreElements());
            assertNull(field(Proxy.getInvocationHandler(results), "target"));
            assertEquals(1, closes.get());
            root.close();
            assertEquals(1, closes.get());
        } finally { f.pool.dispose(); }
    }

    @Test void searchResultAttributesAreDetachedAndNullResultsAreSafe() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            Attribute provider = new BasicAttribute("cn", "name");
            Attributes original = new BasicAttributes(); original.put(provider);
            var result = new javax.naming.directory.SearchResult("cn=name", null, original);
            f.created.get(0).derived = new NamingEnumeration<NameClassPair>() {
                public NameClassPair next() { return result; }
                public boolean hasMore() { return true; }
                public void close() { }
                public boolean hasMoreElements() { return true; }
                public NameClassPair nextElement() { return result; }
            };
            Iterator<NameClassPair> iterator = root.list("").asIterator();
            var returned = (javax.naming.directory.SearchResult) iterator.next();
            assertSame(result, returned);
            assertNull(returned.getObject());
            Attribute detached = returned.getAttributes().get("cn");
            assertNotSame(provider, detached);
            root.close();
            assertEquals("name", detached.get());
            assertNull(field(detached, "source"));
        } finally { f.pool.dispose(); }
    }

    @Test void nullContextFactoryResultDoesNotCorruptRetryState() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var provider = new LdapConnectionPoolTest.Fake();
        try (LdapConnectionPool pool = new LdapConnectionPool(new Properties(), 1, 0, 0,
                p -> attempts.incrementAndGet() == 1 ? null : provider.context, System::nanoTime)) {
            assertThrows(NullPointerException.class, pool::open);
            pool.borrow().close();
            assertEquals(2, attempts.get());
        }
        assertEquals(1, provider.closes);
    }

    @Test void searchSnapshotFailureStillClosesItsChildContext() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            var child = new LdapConnectionPoolTest.Fake();
            NullPointerException failure = new NullPointerException("bad attributes");
            Attributes attributes = (Attributes) Proxy.newProxyInstance(Attributes.class.getClassLoader(),
                    new Class<?>[]{Attributes.class}, (p, m, a) -> {
                        if (m.getName().equals("isCaseIgnored")) return false;
                        throw failure;
                    });
            var result = new javax.naming.directory.SearchResult("child", child.context, attributes);
            f.created.get(0).derived = new NamingEnumeration<NameClassPair>() {
                public NameClassPair next() { return result; }
                public boolean hasMore() { return true; }
                public void close() { }
                public boolean hasMoreElements() { return true; }
                public NameClassPair nextElement() { return result; }
            };
            var enumeration = root.list("");
            assertSame(failure, assertThrows(NullPointerException.class, enumeration::next));
            root.close();
            assertEquals(1, child.closes);
        } finally { f.pool.dispose(); }
    }

    @Test void tlsResponseClosesOnceAndDoesNotRetainProviderAfterReturn() throws Exception {
        var f = new LdapConnectionPoolTest.Fixture(1, 0, 0);
        try {
            LdapContext root = f.pool.borrow();
            AtomicInteger closes = new AtomicInteger();
            var tls = new javax.naming.ldap.StartTlsResponse() {
                public void setEnabledCipherSuites(String[] suites) { }
                public void setHostnameVerifier(javax.net.ssl.HostnameVerifier verifier) { }
                public javax.net.ssl.SSLSession negotiate() { return null; }
                public javax.net.ssl.SSLSession negotiate(javax.net.ssl.SSLSocketFactory factory) { return null; }
                public void close() { closes.incrementAndGet(); }
            };
            f.created.get(0).derived = tls;
            var returned = (javax.naming.ldap.StartTlsResponse) root.extendedOperation(new javax.naming.ldap.StartTlsRequest());
            assertNotSame(tls, returned);
            returned.setHostnameVerifier(null);
            assertNull(returned.negotiate());
            var serialized = (javax.naming.ldap.StartTlsResponse) serializedCopy(returned);
            assertThrows(java.io.IOException.class, serialized::negotiate);
            assertThrows(IllegalStateException.class, () -> serialized.setHostnameVerifier(null));
            serialized.close();
            root.close(); returned.close();
            assertEquals(1, closes.get());
            Object handler = field(returned, "resource");
            assertNull(field(handler, "target")); assertNull(field(handler, "lease"));
            assertThrows(java.io.IOException.class, returned::negotiate);
            assertThrows(IllegalStateException.class, () -> returned.setEnabledCipherSuites(null));
        } finally { f.pool.dispose(); }
    }
}
