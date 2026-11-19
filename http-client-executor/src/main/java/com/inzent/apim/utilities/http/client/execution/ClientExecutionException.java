package com.inzent.apim.utilities.http.client.execution;

public final class ClientExecutionException
		extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ClientExecutionException(String message,
	                                Throwable cause) {
		super(message,
		      cause);
	}
}
