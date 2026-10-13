package com.inzent.apim.utilities.ldap;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.ldap.StartTlsResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.NoSuchElementException;
import java.util.Objects;

import static com.inzent.apim.utilities.ldap.LdapConnectionPool.objectMethod;

final class LdapContextResource implements InvocationHandler {
    private volatile LdapContextLease lease;
    private Object target;
    private final boolean rootAlias;
    Object proxy;
    private volatile boolean exhausted;
    LdapContextResource(LdapContextLease lease, Object target) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.target = Objects.requireNonNull(target, "target");
        this.rootAlias = lease.entry != null && target == lease.entry.context;
    }
    boolean belongsTo(LdapContextLease candidate) { return lease == candidate; }
    boolean wraps(Object candidate) { return target == candidate; }
    void close() throws Exception {
        Object closing = target;
        LdapContextLease active = lease;
        LdapPoolEntry current = active == null ? null : active.entry;
        target = null;
        lease = null;
        proxy = null;
        if (active != null) active.unregister(this);
        try {
            // A provider may return the root itself; it must only be closed by the pool.
            if (closing instanceof Context context && !rootAlias) context.close();
            else if (closing instanceof NamingEnumeration<?> enumeration) enumeration.close();
            else if (closing instanceof StartTlsResponse tls) tls.close();
        } catch (Exception | Error failure) {
            if (current != null) current.dirty = true;
            throw failure;
        }
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
        if (exhausted) {
            if (method.getName().equals("hasMore") || method.getName().equals("hasMoreElements")) return false;
            if (method.getName().equals("next") || method.getName().equals("nextElement")) throw new NoSuchElementException();
        }
        LdapContextLease active = lease;
        LdapPoolEntry current = active == null ? null : active.entry;
        if (current == null) {
            if (method.getName().equals("close")) return null;
            throw closedException(method);
        }
        synchronized (current) {
            if (target == null || current.context == null) {
                if (method.getName().equals("close")) return null;
                throw closedException(method);
            }
            if (method.getName().equals("close")) {
                try { close(); }
                catch (Throwable failure) { current.dirty = true; throw failure; }
                return null;
            }
            if (target instanceof NamingEnumeration<?> && method.getName().equals("asIterator"))
                return new LdapEnumerationIterator((NamingEnumeration<?>) proxy);
            Object result = active.delegate(target, current, method, args);
            if (target instanceof NamingEnumeration<?>
                    && (method.getName().equals("hasMore") || method.getName().equals("hasMoreElements"))
                    && Boolean.FALSE.equals(result)) {
                close();
                exhausted = true;
            }
            return result;
        }
    }

    private static Exception closedException(Method method) {
        if (method.getName().equals("hasMoreElements") || method.getName().equals("nextElement")
                || method.getName().equals("asIterator"))
            return new IllegalStateException("LDAP derived resource is closed");
        return new NamingException("LDAP derived resource is closed");
    }
}

