package com.inzent.apim.utilities.http.client.model;

import java.util.List;

import com.inzent.apim.utilities.http.client.store.BodyStore;

public record HttpResponsePayload(ResponseStatus status,
                                  ConnectVersion version,
                                  HttpHeaders headers,
                                  HttpHeaders trailers,
                                  BodyStore body,
                                  List<ResponseStatus> interim,
                                  boolean complete) {
	public HttpResponsePayload {
		interim = List.copyOf(interim);
	}
}
