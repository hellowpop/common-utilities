package com.inzent.apim.utilities.ldap;

@FunctionalInterface
interface LdapCleanupAction {
    void close() throws Exception;
}
