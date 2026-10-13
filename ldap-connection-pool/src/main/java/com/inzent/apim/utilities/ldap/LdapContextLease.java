package com.inzent.apim.utilities.ldap;

import javax.naming.Context;
import javax.naming.Binding;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

import static com.inzent.apim.utilities.ldap.LdapConnectionPool.cleanup;
import static com.inzent.apim.utilities.ldap.LdapConnectionPool.throwFailure;
import static com.inzent.apim.utilities.ldap.LdapConnectionPool.objectMethod;

final class LdapContextLease implements InvocationHandler {
    volatile LdapPoolEntry entry;
    private LdapConnectionPool owner;
    private List<LdapContextResource> resources = new ArrayList<>();
    private Set<WeakReference<LdapAttributeSnapshot>> attributes = new HashSet<>();
    private ReferenceQueue<LdapAttributeSnapshot> attributeQueue = new ReferenceQueue<>();
    LdapContextLease(LdapConnectionPool owner, LdapPoolEntry entry) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.entry = Objects.requireNonNull(entry, "entry");
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
        LdapPoolEntry current = entry;
        if (current == null) {
            if (method.getName().equals("close")) return null;
            throw new NamingException("LDAP context lease is closed");
        }
        synchronized (current) {
            if (entry == null || current.context == null) {
                if (method.getName().equals("close")) return null;
                throw new NamingException("LDAP context lease is closed");
            }
            if (method.getName().equals("close")) {
                LdapConnectionPool pool = owner;
                Throwable failure = invalidate();
                if (failure != null) current.dirty = true;
                throwFailure(cleanup(failure, () -> pool.release(current)));
                return null;
            }
            return delegate(current.context, current, method, args);
        }
    }

    Object delegate(Object target, LdapPoolEntry current, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "addToEnvironment", "removeFromEnvironment", "setRequestControls", "reconnect", "extendedOperation":
                current.dirty = true;
        }
        try {
            Object value = method.invoke(target, args);
            // SearchResult extends Binding. Keep provider-specific subtypes and controls intact.
            if (value instanceof Binding binding && binding.getObject() instanceof Context) {
                binding.setObject(wrap(binding.getObject(), current));
            }
            if (value instanceof SearchResult result && result.getAttributes() != null)
                result.setAttributes(snapshot(result.getAttributes(), current));
            return wrap(value, current);
        } catch (InvocationTargetException ex) {
            current.dirty = true;
            throw ex.getCause();
        } catch (Throwable failure) {
            current.dirty = true;
            throw failure;
        }
    }

    Object wrap(Object value, LdapPoolEntry current) throws NamingException {
        if (value instanceof Attributes original) return snapshot(original, current);
        if (value instanceof Attribute original) return snapshot(original, current);
        if (value instanceof Context || value instanceof NamingEnumeration<?>) {
            if (value instanceof Context) current.dirty = true;
            if (Proxy.isProxyClass(value.getClass())
                    && Proxy.getInvocationHandler(value) instanceof LdapContextResource resource
                    && resource.belongsTo(this)) return value;
            for (LdapContextResource existing : resources) {
                if (existing.wraps(value)) return existing.proxy;
            }
            LdapContextResource resource = new LdapContextResource(this, value);
            Class<?> type = value instanceof LdapContext ? LdapContext.class
                    : value instanceof DirContext ? DirContext.class
                    : value instanceof Context ? Context.class : NamingEnumeration.class;
            Object wrapped = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, resource);
            resource.proxy = wrapped;
            resources.add(resource);
            return wrapped;
        }
        if (value instanceof StartTlsResponse tls) {
            for (LdapContextResource existing : resources) {
                if (existing.wraps(tls)) return existing.proxy;
            }
            LdapContextResource resource = new LdapContextResource(this, tls);
            StartTlsResponse wrapped = new LdapStartTlsResponse(resource);
            resource.proxy = wrapped;
            resources.add(resource);
            return wrapped;
        }
        return value;
    }

    void unregister(LdapContextResource resource) {
        if (resources != null) resources.remove(resource);
    }

    void register(LdapAttributeSnapshot attribute) {
        // Attribute snapshots own no connections. Weak registration lets unused data be collected.
        for (var collected = attributeQueue.poll(); collected != null; collected = attributeQueue.poll())
            attributes.remove(collected);
        attributes.add(new WeakReference<>(attribute, attributeQueue));
    }

    private Attributes snapshot(Attributes original, LdapPoolEntry current) throws NamingException {
        Attributes detached = new BasicAttributes(original.isCaseIgnored());
        NamingEnumeration<? extends Attribute> values = original.getAll();
        Throwable failure = null;
        try {
            while (values.hasMore()) detached.put(snapshot(values.next(), current));
        } catch (Throwable ex) { failure = ex; }
        throwFailure(cleanup(failure, values::close));
        return detached;
    }

    private Attribute snapshot(Attribute original, LdapPoolEntry current) throws NamingException {
        if (original instanceof LdapAttributeSnapshot existing && existing.belongsTo(this))
            return (Attribute) existing.clone();
        LdapAttributeSnapshot detached = new LdapAttributeSnapshot(this, original);
        NamingEnumeration<?> values = original.getAll();
        Throwable failure = null;
        try {
            while (values.hasMore()) detached.add(wrap(values.next(), current));
        } catch (Throwable ex) { failure = ex; }
        throwFailure(cleanup(failure, values::close));
        register(detached);
        return detached;
    }

    Throwable invalidate() {
        List<LdapContextResource> closing = resources;
        resources = null;
        owner = null;
        entry = null;
        Set<WeakReference<LdapAttributeSnapshot>> detachedAttributes = attributes;
        attributes = null;
        attributeQueue = null;
        if (detachedAttributes != null) {
            for (WeakReference<LdapAttributeSnapshot> reference : detachedAttributes) {
                LdapAttributeSnapshot attribute = reference.get();
                if (attribute != null) attribute.detach();
            }
            detachedAttributes.clear();
        }
        if (closing == null) return null;
        Throwable failure = null;
        for (int i = closing.size() - 1; i >= 0; i--) failure = cleanup(failure, closing.get(i)::close);
        closing.clear();
        return failure;
    }
}

