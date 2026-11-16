package com.inzent.apim.utilities.http.mockserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class HttpResponseWriter {
    private HttpResponseWriter() {}
    static void write(OutputStream output, MockResponse response) throws IOException {
        String reason = switch (response.statusCode()) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 205 -> "Reset Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 408 -> "Request Timeout";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
        String headers = "HTTP/1.1 " + response.statusCode() + " " + reason + "\r\n"
            + "Content-Type: " + response.contentType() + "\r\n"
            + "Content-Length: " + response.contentLength() + "\r\n"
            + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        response.writeBody(output);
        output.flush();
    }
}
