package com.inzent.apim.utilities.http.client.config;

public record Ht5Options(int reactorThreads) {
	public Ht5Options {
		if (reactorThreads < 1) {
			throw new IllegalArgumentException("Positive reactor thread count required");
		}
	}
}
