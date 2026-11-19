package com.inzent.apim.utilities.http.client.connector.ht5;

import org.apache.hc.client5.http.EndpointInfo;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.async.AsyncExecRuntime;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.command.RequestExecutionCommand;
import org.apache.hc.core5.reactor.Command;
import org.apache.hc.core5.util.TimeValue;

/**
 * Uses HttpCore's per-stream cancellation instead of closing a shared client endpoint.
 */
final class Ht5StreamRuntime
		implements AsyncExecRuntime {
	private final AsyncExecRuntime delegate;
	private final Ht5ConnectionRegistry connections;
	private final CancellableDependency cancellation;

	Ht5StreamRuntime(AsyncExecRuntime delegate,
	                 Ht5ConnectionRegistry connections,
	                 CancellableDependency cancellation) {
		this.delegate = delegate;
		this.connections = connections;
		this.cancellation = cancellation;
	}

	@Override
	public Cancellable execute(String id,
	                           AsyncClientExchangeHandler handler,
	                           HttpClientContext context) {
		EndpointInfo info = this.delegate.getEndpointInfo();
		ManagedAsyncClientConnection connection = info == null
		                                          ? null
		                                          : this.connections.find(info.getSslSession());
		if (connection == null || !HttpVersion.HTTP_2.equals(connection.getProtocolVersion())) {
			throw new IllegalStateException("Apache HTTP/2 transport unavailable");
		}
		context.setProtocolVersion(connection.getProtocolVersion());
		context.setSSLSession(connection.getSSLSession());
		context.setEndpointDetails(connection.getEndpointDetails());
		connection.submitCommand(new RequestExecutionCommand(handler,
		                                                     null,
		                                                     this.cancellation,
		                                                     context),
		                         Command.Priority.NORMAL);
		return this.cancellation;
	}

	void releaseAfterStreamFailure() {
		// This runtime is only installed after the H2 route is ready. A draining endpoint may
		// no longer expose EndpointInfo, but must still be released rather than closed immediately.
		if (this.delegate.isEndpointAcquired()) {
			this.delegate.markConnectionReusable(null,
			                                     TimeValue.NEG_ONE_MILLISECOND);
			this.delegate.releaseEndpoint();
		}
	}

	@Override
	public boolean isEndpointAcquired() {
		return this.delegate.isEndpointAcquired();
	}

	@Override
	public boolean isEndpointConnected() {
		return this.delegate.isEndpointConnected();
	}

	@Override
	public EndpointInfo getEndpointInfo() {
		return this.delegate.getEndpointInfo();
	}

	@Override
	public Cancellable acquireEndpoint(String id,
	                                   HttpRoute route,
	                                   Object state,
	                                   HttpClientContext context,
	                                   FutureCallback<AsyncExecRuntime> callback) {
		return this.delegate.acquireEndpoint(id,
		                                     route,
		                                     state,
		                                     context,
		                                     callback);
	}

	@Override
	public Cancellable connectEndpoint(HttpClientContext context,
	                                   FutureCallback<AsyncExecRuntime> callback) {
		return this.delegate.connectEndpoint(context,
		                                     callback);
	}

	@Override
	public void releaseEndpoint() {
		this.delegate.releaseEndpoint();
	}

	@Override
	public void discardEndpoint() {
		this.delegate.discardEndpoint();
	}

	@Override
	public void disconnectEndpoint() {
		this.delegate.disconnectEndpoint();
	}

	@Override
	public void upgradeTls(HttpClientContext context) {
		this.delegate.upgradeTls(context);
	}

	@Override
	public void upgradeTls(HttpClientContext context,
	                       FutureCallback<AsyncExecRuntime> callback) {
		this.delegate.upgradeTls(context,
		                         callback);
	}

	@Override
	public boolean validateConnection() {
		return this.delegate.validateConnection();
	}

	@Override
	public void markConnectionReusable(Object state,
	                                   TimeValue duration) {
		this.delegate.markConnectionReusable(state,
		                                     duration);
	}

	@Override
	public void markConnectionNonReusable() {
		this.delegate.markConnectionNonReusable();
	}

	@Override
	public AsyncExecRuntime fork() {
		return new Ht5StreamRuntime(this.delegate.fork(),
		                            this.connections,
		                            this.cancellation);
	}
}
