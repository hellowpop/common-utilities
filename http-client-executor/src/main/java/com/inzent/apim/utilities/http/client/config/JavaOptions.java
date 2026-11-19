package com.inzent.apim.utilities.http.client.config;

public record JavaOptions(int readBufferSize) {
	public JavaOptions {
		if (readBufferSize < 1 || readBufferSize > 1024 * 1024) {
			throw new IllegalArgumentException("Read buffer must be 1..1048576 bytes");
		}
	}
}
