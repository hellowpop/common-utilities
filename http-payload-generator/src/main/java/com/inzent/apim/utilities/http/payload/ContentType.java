package com.inzent.apim.utilities.http.payload;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Consumer;

/** Supported dummy representations. Lengths are bytes, using ASCII data. */
public enum ContentType {
    TEXT_PLAIN("text/plain; charset=UTF-8", "", "", "", "key%d=", "\n", ' '),
    APPLICATION_JSON("application/json; charset=UTF-8", "{", "}", ",", "\"key%d\":\"", "\"", ' '),
    APPLICATION_XML("application/xml; charset=UTF-8", "<root>", "</root>", "", "<key%d>", "</key%d>", ' '),
    TEXT_HTML("text/html; charset=UTF-8", "<html><body>", "</body></html>", "", "<div data-key=\"key%d\">", "</div>", ' '),
    APPLICATION_FORM_URLENCODED("application/x-www-form-urlencoded; charset=UTF-8", "", "", "&", "key%d=", "", '&'),
    APPLICATION_OCTET_STREAM("application/octet-stream", "", "", "", "", "", 'a');

    final String open, close, separator, key, end;
    final char padding;
    private final String mediaType;

    ContentType(String mediaType, String open, String close, String separator, String key, String end, char padding) {
        this.mediaType = mediaType;
        this.open = open;
        this.close = close;
        this.separator = separator;
        this.key = key;
        this.end = end;
        this.padding = padding;
    }

    public String mediaType() { return mediaType; }
    public int minimumLength() { return open.length() + close.length(); }

    /** Invokes the callback synchronously; the stream closes when it returns. */
    public void generate(long contentLength, long blockSize, Consumer<InputStream> acceptor) {
        Objects.requireNonNull(acceptor, "acceptor");
        validate(contentLength, blockSize);
        try (InputStream stream = new DummyInputStream(this, contentLength, blockSize)) {
            acceptor.accept(stream);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    void validate(long length, long blockSize) {
        if (length < 0) throw new IllegalArgumentException("contentLength must be >= 0");
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize must be > 0");
        if (length != 0 && length < minimumLength())
            throw new IllegalArgumentException("contentLength must be 0 or >= " + minimumLength() + " for " + name());
    }
}
