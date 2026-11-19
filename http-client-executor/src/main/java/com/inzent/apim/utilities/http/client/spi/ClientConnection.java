package com.inzent.apim.utilities.http.client.spi;

import java.io.IOException;

import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;

public interface ClientConnection
		extends AutoCloseable {
	HttpResponsePayload exchange(ExecutionContext context) throws Exception;

	boolean reusable();

	default boolean multiplexed() {
		return false;
	}

	default int streamCapacity() {
		return 1;
	}

	default boolean deferredConnectEvents() {
		return false;
	}

	@Override
	void close() throws IOException;
}
