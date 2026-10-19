package com.inzent.apim.utilities.http.curl;

import com.inzent.apim.utilities.http.payload.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import java.io.*;
import java.util.*;

/** Bridges the existing enum generator to Netty HTTP values and context-owned entity storage. */
final class PayloadPreparation {
    private static final List<CharSequence> RESERVED = List.of(HttpHeaderNames.CONTENT_TYPE, HttpHeaderNames.CONTENT_ENCODING,
        HttpHeaderNames.CONTENT_LENGTH, HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderNames.HOST);
    private PayloadPreparation() { }

    static void defaults(ExecuteContext c) {
        HttpHeaders expected = base(c);
        // Compressed length is known only after generation; validate it in prepare().
        if (!CurlArguments.noBody(c)) {
            if (c.chunked()) expected.set(HttpHeaderNames.TRANSFER_ENCODING,HttpHeaderValues.CHUNKED);
            else if (c.contentEncoding() == ContentEncoding.IDENTITY) expected.set(HttpHeaderNames.CONTENT_LENGTH,c.contentLength());
        }
        merge(c.mutableHeaders(),expected,false);
    }
    static void prepare(ExecuteContext c) throws IOException {
        BodyStore body = c.newBody();
        HttpHeaders expected = base(c);
        if (CurlArguments.noBody(c)) body.copyFrom(InputStream.nullInputStream());
        else {
            new DefaultHttpPayloadGenerator().generate(c.chunked(), c.contentType(), c.contentEncoding(),
                c.contentLength(), c.blockSize(), (generated,input) -> {
                    if (!Objects.equals(generated.get(HttpHeaderNames.CONTENT_TYPE),expected.get(HttpHeaderNames.CONTENT_TYPE))
                        || !Objects.equals(generated.get(HttpHeaderNames.CONTENT_ENCODING),expected.get(HttpHeaderNames.CONTENT_ENCODING)))
                        throw new IllegalStateException("Payload generator and Netty content mappings disagree");
                    try { body.copyFrom(input); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            if (c.chunked()) expected.set(HttpHeaderNames.TRANSFER_ENCODING,HttpHeaderValues.CHUNKED);
            else expected.set(HttpHeaderNames.CONTENT_LENGTH,body.size());
        }
        merge(c.mutableHeaders(),expected,true);
        // Revalidate even if a pre hook supplied a non-validating Netty implementation/value.
        HttpHeaders validated = new DefaultHttpHeaders();
        c.mutableHeaders().forEach(e -> validated.add(e.getKey(),e.getValue()));
        if (validated.contains(HttpHeaderNames.EXPECT)) throw new IllegalArgumentException("Expect is managed by the executor");
        CurlArguments.validateHeaderArguments(c, validated);
        c.record().request().prepare(validated,body);
    }
    private static HttpHeaders base(ExecuteContext c) {
        HttpHeaders headers = new DefaultHttpHeaders();
        String host = c.url().getHost();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = '['+host+']';
        int port = c.url().getPort();
        if (port != -1 && !(port == 80 && c.url().getScheme().equalsIgnoreCase("http"))
                && !(port == 443 && c.url().getScheme().equalsIgnoreCase("https"))) host += ":"+port;
        headers.set(HttpHeaderNames.HOST,host);
        if (!CurlArguments.noBody(c)) {
            CharSequence mediaType = switch (c.contentType()) {
                case TEXT_PLAIN -> HttpHeaderValues.TEXT_PLAIN;
                case APPLICATION_JSON -> HttpHeaderValues.APPLICATION_JSON;
                case APPLICATION_XML -> HttpHeaderValues.APPLICATION_XML;
                case TEXT_HTML -> HttpHeaderValues.TEXT_HTML;
                case APPLICATION_FORM_URLENCODED -> HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED;
                case APPLICATION_OCTET_STREAM -> HttpHeaderValues.APPLICATION_OCTET_STREAM;
            };
            String value = mediaType.toString();
            if (c.contentType() != ContentType.APPLICATION_OCTET_STREAM)
                value += "; "+HttpHeaderValues.CHARSET+'='+CharsetUtil.UTF_8.name();
            headers.set(HttpHeaderNames.CONTENT_TYPE,value);
            CharSequence encoding = switch(c.contentEncoding()) {
                case IDENTITY -> HttpHeaderValues.IDENTITY;
                case GZIP -> HttpHeaderValues.GZIP;
                case DEFLATE -> HttpHeaderValues.DEFLATE;
                case BR -> HttpHeaderValues.BR;
                case ZSTD -> HttpHeaderValues.ZSTD;
                case SNAPPY -> HttpHeaderValues.SNAPPY;
            };
            if (c.contentEncoding() != ContentEncoding.IDENTITY) headers.set(HttpHeaderNames.CONTENT_ENCODING,encoding);
        }
        return headers;
    }
    private static void merge(HttpHeaders target, HttpHeaders expected, boolean finalCheck) {
        for (CharSequence name : RESERVED) {
            List<String> actual = target.getAll(name), required = expected.getAll(name);
            if (!actual.isEmpty() && (!required.isEmpty() || finalCheck) && !actual.equals(required))
                throw new IllegalArgumentException("Managed header conflict: "+name);
            if (actual.isEmpty() && !required.isEmpty()) target.set(name,required);
        }
    }
}
