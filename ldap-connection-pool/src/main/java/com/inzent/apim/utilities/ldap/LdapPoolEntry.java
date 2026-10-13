package com.inzent.apim.utilities.ldap;

import javax.naming.NamingException;
import javax.naming.ldap.LdapContext;
import java.util.Objects;

import static com.inzent.apim.utilities.ldap.LdapConnectionPool.cleanup;
import static com.inzent.apim.utilities.ldap.LdapConnectionPool.throwFailure;

final class LdapPoolEntry {
    LdapContext context;
    volatile LdapContextLease lease;
    long idleSince;
    boolean dirty;
    LdapPoolEntry(LdapContext context, long idleSince) {
        this.context = Objects.requireNonNull(context, "context");
        this.idleSince = idleSince;
    }
    synchronized void destroy() throws NamingException {
        if (context == null) return;
        LdapContext closing = context;
        context = null;
        Throwable failure = null;
        if (lease != null) { failure = lease.invalidate(); lease = null; }
        throwFailure(cleanup(failure, closing::close));
    }
}

