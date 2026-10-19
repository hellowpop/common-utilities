package com.inzent.apim.utilities.http.curl;

/** The context retains the execution record and any partial response. */
public final class CurlExecutionException extends RuntimeException {
    public CurlExecutionException(String message, Throwable cause) { super(message, cause); }
}
