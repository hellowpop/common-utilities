package com.inzent.apim.utilities.http.mockserver;

/** Default point identifiers. Callers may also supply their own enum constants. */
public enum MockPoint {
    ON_CONNECTED, BEFORE_READ, READ_CHUNK, ON_PEER_EOF, ON_CHUNK_RECEIVED,
    DELAY_BEFORE_NEXT_READ, READ_HTTP_PROTOCOL
}
