package com.inzent.apim.utilities.netty.http.mockserver.response;
/** Separates deliberate request validation failures from processor defects. */
public final class RequestValidationException extends IllegalArgumentException {
    public RequestValidationException(String code) { super(code); }
}
