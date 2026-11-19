package com.inzent.apim.utilities.http.client.config;

public record NettyOptions(int eventLoopThreads) {
	public NettyOptions {
		if (eventLoopThreads < 1) {
			throw new IllegalArgumentException("Positive event loop thread count required");
		}
	}
}
