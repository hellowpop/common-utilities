package com.inzent.apim.utilities.http.mockserver;

public final class MockServerStopException extends RuntimeException {
    public MockServerStopException(String message) { super(message); }
    public MockServerStopException(String message, Throwable cause) { super(message, cause); }
}
