package com.inzent.apim.utilities.http.client.interceptor;

import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;

/**
 * Notifications run on execute's calling thread. Only contentGenerated controls execution.
 */
public interface ClientHandleInterceptor {
	default byte[] contentGenerated(byte[] content) {
		return null;
	}

	default void connectResult(ConnectResult result) {
	}

	default void requestSent(long bodyBytes) {
	}

	default void responseReceived(HttpResponsePayload response) {
	}

	record ConnectResult(boolean success,
	                     boolean reused,
	                     Throwable failure) {
	}
}
