package com.inzent.apim.utilities.ldap;

import javax.naming.ldap.StartTlsResponse;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Objects;

/** TLS operations share the lease lock; the handler detaches provider references on close. */
final class LdapStartTlsResponse extends StartTlsResponse {
    private static final long serialVersionUID = 1L;
    private final transient LdapContextResource resource;

    LdapStartTlsResponse(LdapContextResource resource) {
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    @Override public void setEnabledCipherSuites(String[] suites) {
        configure("setEnabledCipherSuites", String[].class, suites);
    }

    @Override public void setHostnameVerifier(HostnameVerifier verifier) {
        configure("setHostnameVerifier", HostnameVerifier.class, verifier);
    }

    @Override public SSLSession negotiate() throws IOException {
        return (SSLSession) call("negotiate", new Class<?>[0], new Object[0]);
    }

    @Override public SSLSession negotiate(SSLSocketFactory factory) throws IOException {
        return (SSLSession) call("negotiate", new Class<?>[]{SSLSocketFactory.class}, new Object[]{factory});
    }

    @Override public void close() throws IOException {
        if (resource != null) call("close", new Class<?>[0], new Object[0]);
    }

    private void configure(String name, Class<?> type, Object argument) {
        try { call(name, new Class<?>[]{type}, new Object[]{argument}); }
        catch (IOException failure) { throw new IllegalStateException("LDAP TLS resource is closed or unavailable", failure); }
    }

    private Object call(String name, Class<?>[] types, Object[] arguments) throws IOException {
        if (resource == null) throw new IOException("LDAP TLS resource is detached");
        try {
            Method method = StartTlsResponse.class.getMethod(name, types);
            return resource.invoke(this, method, arguments);
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IOException("LDAP TLS operation failed", failure); }
    }
}
