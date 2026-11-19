package com.inzent.apim.utilities.http.client.connector.ht5;

import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import javax.net.ssl.SSLSession;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.impl.nio.DefaultAsyncClientConnectionOperator;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.util.Timeout;

/**
 * Tracks Apache's own connections so stream commands can carry their cancellation dependency.
 */
final class Ht5ConnectionRegistry
		extends DefaultAsyncClientConnectionOperator {
	private final List<ManagedAsyncClientConnection> connections = new CopyOnWriteArrayList<>();

	Ht5ConnectionRegistry(TlsStrategy tls,
	                      SchemePortResolver schemeResolver,
	                      DnsResolver dnsResolver) {
		super(RegistryBuilder.<TlsStrategy>create()
		                     .register("https",
		                               tls)
		                     .build(),
		      schemeResolver,
		      dnsResolver);
	}

	@Override
	public Future<ManagedAsyncClientConnection> connect(ConnectionInitiator initiator,
	                                                    HttpHost host,
	                                                    Path path,
	                                                    NamedEndpoint target,
	                                                    SocketAddress local,
	                                                    Timeout timeout,
	                                                    Object attachment,
	                                                    HttpContext context,
	                                                    FutureCallback<ManagedAsyncClientConnection> callback) {
		return super.connect(initiator,
		                     host,
		                     path,
		                     target,
		                     local,
		                     timeout,
		                     attachment,
		                     context,
		                     new FutureCallback<ManagedAsyncClientConnection>() {
			                     @Override
			                     public void completed(ManagedAsyncClientConnection connection) {
				                     Ht5ConnectionRegistry.this.connections.removeIf(existing -> !existing.isOpen());
				                     Ht5ConnectionRegistry.this.connections.add(connection);
				                     callback.completed(connection);
			                     }

			                     @Override
			                     public void failed(Exception failure) {
				                     callback.failed(failure);
			                     }

			                     @Override
			                     public void cancelled() {
				                     callback.cancelled();
			                     }
		                     });
	}

	ManagedAsyncClientConnection find(SSLSession session) {
		if (session == null) {
			return null;
		}
		for (ManagedAsyncClientConnection connection : this.connections) {
			SSLSession candidate = connection.getSSLSession();
			if (connection.isOpen() && candidate != null && (session == candidate || Arrays.equals(session.getId(),
			                                                                                       candidate.getId()))) {
				return connection;
			}
		}
		return null;
	}

	void clear() {
		this.connections.clear();
	}
}
