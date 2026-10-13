package com.inzent.apim.utilities.ldap;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import java.lang.reflect.Method;
import java.util.Objects;

import static com.inzent.apim.utilities.ldap.LdapConnectionPool.throwFailure;

/** Detached attribute values; provider-backed schema access exists only during the lease. */
final class LdapAttributeSnapshot extends BasicAttribute {
    private static final long serialVersionUID = 1L;
    private transient volatile LdapContextLease lease;
    private transient Attribute source;

    LdapAttributeSnapshot(LdapContextLease lease, Attribute source) {
        super(Objects.requireNonNull(source, "source").getID(), source.isOrdered());
        this.lease = Objects.requireNonNull(lease, "lease");
        this.source = source;
    }

    boolean belongsTo(LdapContextLease candidate) { return lease == candidate; }

    void detach() { source = null; lease = null; }

    @Override public DirContext getAttributeDefinition() throws NamingException {
        return schema("getAttributeDefinition");
    }

    @Override public DirContext getAttributeSyntaxDefinition() throws NamingException {
        return schema("getAttributeSyntaxDefinition");
    }

    private DirContext schema(String methodName) throws NamingException {
        LdapContextLease active = lease;
        LdapPoolEntry current = active == null ? null : active.entry;
        if (current == null) throw new NamingException("LDAP attribute schema lease is closed");
        synchronized (current) {
            if (source == null || active.entry != current || current.context == null)
                throw new NamingException("LDAP attribute schema lease is closed");
            try {
                Method method = Attribute.class.getMethod(methodName);
                return (DirContext) active.delegate(source, current, method, null);
            } catch (Throwable failure) {
                current.dirty = true;
                throwFailure(failure);
                throw new AssertionError("unreachable");
            }
        }
    }

    @Override public Object clone() {
        LdapContextLease active = lease;
        LdapPoolEntry current = active == null ? null : active.entry;
        if (current == null) {
            LdapAttributeSnapshot copy = (LdapAttributeSnapshot) super.clone();
            copy.detach();
            return copy;
        }
        synchronized (current) {
            LdapAttributeSnapshot copy = (LdapAttributeSnapshot) super.clone();
            if (source == null || active.entry != current || current.context == null) copy.detach();
            else active.register(copy);
            return copy;
        }
    }
}
