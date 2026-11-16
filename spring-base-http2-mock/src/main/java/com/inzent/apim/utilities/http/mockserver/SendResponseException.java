package com.inzent.apim.utilities.http.mockserver;

import java.util.Objects;

public final class SendResponseException extends NextActionException {
    private final MockResponse response;
    public SendResponseException(MockResponse response) {
        super("Send response and close client");
        this.response = Objects.requireNonNull(response, "response");
    }
    public MockResponse response() { return response; }
}
