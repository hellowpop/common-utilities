package com.inzent.apim.utilities.http.curl;

import io.netty.handler.codec.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Bounded parsing of a single-verbose stderr log; the original log is never altered. */
final class CurlLogParser {
    private static final int HEADER_LIMIT = 262144, BLOCK_LIMIT = 32, DIAGNOSTIC_LIMIT = 65536;
    private CurlLogParser() { }
    static void parse(Path path, ExecutionRecord record) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            file.seek(Math.max(0,length-DIAGNOSTIC_LIMIT));
            byte[] tail = new byte[(int)Math.min(length,DIAGNOSTIC_LIMIT)]; file.readFully(tail);
            record.diagnostics(new String(tail,StandardCharsets.ISO_8859_1), length > DIAGNOSTIC_LIMIT);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            HttpHeaders headers = null;
            HttpResponseStatus status = null;
            boolean sending = false;
            int bytes = 0, blocks = 0;
            String line;
            while ((line = readLine(in,record)) != null) {
                if (!(line.startsWith("> ") || line.startsWith("< "))) continue;
                boolean sendLine = line.charAt(0) == '>';
                String text = line.substring(2);
                if (text.startsWith("HTTP/")) {
                    if (headers != null) save(record,headers,status,sending);
                    if (++blocks > BLOCK_LIMIT) throw limit(record);
                    String[] parts = text.split(" ",3);
                    try { status = new HttpResponseStatus(Integer.parseInt(parts[1]),parts.length > 2 ? parts[2] : ""); }
                    catch (RuntimeException e) { throw new IOException("Malformed HTTP status in curl log",e); }
                    headers = new DefaultHttpHeaders(); sending = false; bytes = line.length()+2;
                } else if (sendLine && text.matches("[A-Z]+ \\S+ HTTP/1\\.[01]")) {
                    if (headers != null) save(record,headers,status,sending);
                    if (++blocks > BLOCK_LIMIT) throw limit(record);
                    headers = new DefaultHttpHeaders(); status = null; sending = true; bytes = line.length()+2;
                } else if (text.isEmpty()) {
                    if (headers != null) { save(record,headers,status,sending); headers = null; status = null; }
                } else if (headers != null && sending == sendLine) {
                    bytes += line.length()+2;
                    if (bytes > HEADER_LIMIT) throw limit(record);
                    int colon = text.indexOf(':');
                    if (colon <= 0) throw new IOException("Malformed header in curl log");
                    try { headers.add(text.substring(0,colon),text.substring(colon+1).strip()); }
                    catch (RuntimeException e) { throw new IOException("Invalid HTTP header in curl log",e); }
                }
            }
            if (headers != null) save(record,headers,status,sending);
        }
    }
    private static void save(ExecutionRecord r, HttpHeaders h, HttpResponseStatus s, boolean sending) {
        if (sending) r.request().sent(h); else if (s != null) r.response().add(s,h);
    }
    private static IOException limit(ExecutionRecord r) { r.headersTruncated(true); return new IOException("Curl header capture limit exceeded"); }
    private static String readLine(InputStream in, ExecutionRecord r) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b = in.read(); if (b == -1) return null;
        boolean header = b == '<' || b == '>';
        while (b != -1 && b != '\n') {
            if (buffer.size() < HEADER_LIMIT) buffer.write(b);
            else if (header) throw limit(r); // Diagnostic lines may be long; discard their excess.
            b = in.read();
        }
        String line = buffer.toString(StandardCharsets.ISO_8859_1);
        int end = line.length();
        while (end > 0 && line.charAt(end-1) == '\r') end--;
        return line.substring(0,end);
    }
}
