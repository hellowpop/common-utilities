package com.inzent.apim.utilities.http.client.model;

import java.util.Objects;

public record ConnectMethod(String token) {
	public static final ConnectMethod GET = new ConnectMethod("GET");
	public static final ConnectMethod POST = new ConnectMethod("POST");
	public static final ConnectMethod PUT = new ConnectMethod("PUT");
	public static final ConnectMethod DELETE = new ConnectMethod("DELETE");
	public static final ConnectMethod HEAD = new ConnectMethod("HEAD");
	public static final ConnectMethod PATCH = new ConnectMethod("PATCH");
	public static final ConnectMethod OPTIONS = new ConnectMethod("OPTIONS");

	public ConnectMethod {
		Objects.requireNonNull(token,
		                       "token");
		if (!token.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
			throw new IllegalArgumentException("Invalid HTTP token");
		}
	}

	public static ConnectMethod of(String token) {
		return new ConnectMethod(token);
	}
}
