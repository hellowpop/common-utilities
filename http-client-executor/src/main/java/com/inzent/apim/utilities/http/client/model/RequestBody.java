package com.inzent.apim.utilities.http.client.model;

import java.io.IOException;
import java.util.Objects;

import com.inzent.apim.utilities.http.client.store.BodyStore;

@FunctionalInterface
public interface RequestBody {
	HttpHeaders prepare(BodyStore target) throws IOException;

	static RequestBody empty() {
		return target -> HttpHeaders.builder()
		                            .build();
	}

	static RequestBody bytes(String contentType,
	                         byte[] bytes) {
		Objects.requireNonNull(contentType);
		byte[] snapshot = bytes.clone();
		return target -> {
			target.write(snapshot);
			return HttpHeaders.builder()
			                  .add("Content-Type",
			                       contentType)
			                  .add("Content-Length",
			                       Integer.toString(snapshot.length))
			                  .build();
		};
	}
}
