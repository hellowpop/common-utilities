package com.inzent.apim.utilities.ldap;

import javax.naming.NamingEnumeration;
import java.util.Iterator;
import java.util.Objects;

/** Keeps Enumeration.asIterator() on the lease-aware proxy rather than the raw provider. */
final class LdapEnumerationIterator implements Iterator<Object> {
    private final NamingEnumeration<?> enumeration;

    LdapEnumerationIterator(NamingEnumeration<?> enumeration) {
        this.enumeration = Objects.requireNonNull(enumeration, "enumeration");
    }

    @Override public boolean hasNext() { return enumeration.hasMoreElements(); }
    @Override public Object next() { return enumeration.nextElement(); }
}
