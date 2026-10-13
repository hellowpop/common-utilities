package com.inzent.apim.utilities.ldap;

import java.util.Properties;
import javax.naming.NamingException;
import javax.naming.ldap.LdapContext;

@FunctionalInterface
interface LdapContextFactory {
    LdapContext create(Properties properties) throws NamingException;
}
