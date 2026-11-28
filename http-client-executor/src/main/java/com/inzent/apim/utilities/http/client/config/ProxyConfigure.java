package com.inzent.apim.utilities.http.client.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public final class ProxyConfigure {
	private final String host;
	private final int port;
	private final String authorization;

	private ProxyConfigure(String host,
	                       int port,
	                       String user,
	                       String password) {
		this.host = Objects.requireNonNull(host);
		if (host.isBlank() || port < 1 || port > 65535) {
			throw new IllegalArgumentException("Invalid proxy address");
		}
		if (user == null && password != null) {
			throw new IllegalArgumentException("Proxy password requires a user");
		}
		this.port = port;
		if (user != null && (user.contains(":") || user.contains("\r") || user.contains("\n"))) {
			throw new IllegalArgumentException("Invalid proxy user");
		}
		this.authorization = user == null
		                     ? null
		                     : "Basic " + Base64.getEncoder()
		                                        .encodeToString((user + ":" + Objects.requireNonNull(password)).getBytes(StandardCharsets.ISO_8859_1));
	}

	public static ProxyConfigure http(String host,
	                                  int port) {
		return new ProxyConfigure(host,
		                          port,
		                          null,
		                          null);
	}

	public static ProxyConfigure basic(String host,
	                                   int port,
	                                   String user,
	                                   String password) {
		return new ProxyConfigure(host,
		                          port,
		                          Objects.requireNonNull(user),
		                          password);
	}

	public String host() {
		return this.host;
	}

	public int port() {
		return this.port;
	}

	public String authorization() {
		return this.authorization;
	}

	@Override
	public String toString() {
		return "ProxyConfigure[" + this.host + ":" + this.port + "]";
	}
}
