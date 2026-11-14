package com.inzent.apim.utilities.http.caller.payload;
import com.inzent.apim.utilities.http.payload.ContentType;
import com.inzent.apim.utilities.http.payload.ContentEncoding;
import java.util.Objects;
public record PayloadSpec(boolean chunked, ContentType contentType, ContentEncoding contentEncoding,
                          long contentLength, long blockSize) {
    public PayloadSpec {
        Objects.requireNonNull(contentType); Objects.requireNonNull(contentEncoding);
        if (contentLength < 0 || blockSize <= 0) throw new IllegalArgumentException("Invalid payload dimensions");
    }
}
