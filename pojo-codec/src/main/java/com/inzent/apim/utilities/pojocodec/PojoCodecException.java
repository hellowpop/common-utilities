package com.inzent.apim.utilities.pojocodec;

import java.io.IOException;

/** Malformed data, unsupported types, or configured limit violations. */
public class PojoCodecException extends IOException {
    public PojoCodecException(String message) { super(message); }
    public PojoCodecException(String message, Throwable cause) { super(message, cause); }
}
