package com.inzent.apim.utilities.http.client.model;

import java.net.URI;

import com.inzent.apim.utilities.http.client.store.BodyStore;

public record HttpRequestPayload(URI url,
                                 ConnectMethod method,
                                 ConnectVersion version,
                                 HttpHeaders headers,
                                 BodyStore generatedBody,
                                 BodyStore body,
                                 WireMode wireMode) {
}
