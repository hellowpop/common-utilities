package com.inzent.apim.utilities.http.client.model;

public record ResponseStatus(int code,
                             String reason) {
	public ResponseStatus {
		if (code < 100 || code > 999) {
			throw new IllegalArgumentException("Invalid status code");
		}
		reason = reason == null
		         ? ""
		         : reason;
	}
}
