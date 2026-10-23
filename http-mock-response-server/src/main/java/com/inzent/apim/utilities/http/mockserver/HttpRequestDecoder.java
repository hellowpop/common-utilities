package com.inzent.apim.utilities.http.mockserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static com.inzent.apim.utilities.http.mockserver.HttpReceiveState.*;

/** Connection-local, incremental HTTP/1 request decoder. Never owns a socket or stream. */
final class HttpRequestDecoder {
    private enum Phase { INITIAL, HEADERS, FIXED, CHUNK_SIZE, CHUNK_DATA, CHUNK_CR, CHUNK_LF, TRAILERS, DONE }
    private static final Set<String> FORBIDDEN_TRAILERS = Set.of(
        "content-length", "transfer-encoding", "host", "connection", "trailer");
    private final HttpProtocolLimits limits;
    private Phase phase = Phase.INITIAL;
    private StringBuilder line = new StringBuilder();
    private boolean carriageReturn;
    private int lineBytes;
    private int headerBytes;
    private int trailerBytes;
    private long chunkFramingBytes;
    private int remaining;
    private String initialLine = "";
    private Map<String, List<String>> headers = new LinkedHashMap<>();
    private Map<String, List<String>> trailers = new LinkedHashMap<>();
    private Map<String, List<String>> frozenHeaders = Map.of();
    private Map<String, List<String>> frozenTrailers = Map.of();
    private HttpContentBuffer content = new HttpContentBuffer();
    private HttpReceiveState initialState = NOT_REACHED;
    private HttpReceiveState headerState = NOT_REACHED;
    private HttpReceiveState contentState = NOT_REACHED;
    private HttpReceiveState trailerState = NOT_REACHED;
    private HttpProtocolResult result = HttpProtocolResult.empty();
    private boolean failed;

    HttpRequestDecoder(HttpProtocolLimits limits) { this.limits = Objects.requireNonNull(limits); }
    HttpProtocolResult result() { return result; }

    HttpProtocolResult feed(ByteBuffer input) throws IOException {
        Objects.requireNonNull(input);
        requireOpen();
        var completed = new ArrayList<HttpRequestSnapshot>();
        try {
            while (input.hasRemaining()) {
                if (phase == Phase.DONE) resetRequest();
                int value = input.get() & 0xff;
                switch (phase) {
                    case INITIAL -> {
                        initialState = RECEIVING;
                        String text = readLine(value);
                        if (text != null) {
                            parseInitialLine(text);
                            initialState = COMPLETE;
                            phase = Phase.HEADERS;
                        }
                    }
                    case HEADERS -> {
                        headerState = RECEIVING;
                        if (headerBytes == limits.headerBytes()) throw invalid("Header section exceeds byte limit");
                        headerBytes++;
                        String text = readLine(value);
                        if (text != null) {
                            if (text.isEmpty()) finishHeaders();
                            else parseField(text, headers, false);
                        }
                    }
                    case FIXED -> {
                        contentState = RECEIVING;
                        content.write(value);
                        if (--remaining == 0) { contentState = COMPLETE; phase = Phase.DONE; }
                    }
                    case CHUNK_SIZE -> {
                        contentState = RECEIVING;
                        countChunkFraming();
                        String text = readLine(value);
                        if (text != null) readChunkSize(text);
                    }
                    case CHUNK_DATA -> {
                        content.write(value);
                        if (--remaining == 0) phase = Phase.CHUNK_CR;
                    }
                    case CHUNK_CR -> {
                        countChunkFraming();
                        if (value != '\r') throw invalid("Missing CR after chunk data");
                        phase = Phase.CHUNK_LF;
                    }
                    case CHUNK_LF -> {
                        countChunkFraming();
                        if (value != '\n') throw invalid("Missing LF after chunk data");
                        phase = Phase.CHUNK_SIZE;
                    }
                    case TRAILERS -> {
                        trailerState = RECEIVING;
                        if (trailerBytes == limits.headerBytes()) throw invalid("Trailer section exceeds byte limit");
                        trailerBytes++;
                        String text = readLine(value);
                        if (text != null) {
                            if (text.isEmpty()) { trailerState = COMPLETE; phase = Phase.DONE; }
                            else parseField(text, trailers, true);
                        }
                    }
                    default -> throw new IllegalStateException("Unexpected decoder phase");
                }
                if (phase == Phase.DONE) completed.add(snapshot());
            }
            HttpRequestSnapshot current = phase == Phase.DONE && !completed.isEmpty()
                ? completed.get(completed.size() - 1) : snapshot();
            result = new HttpProtocolResult(current, completed, false);
            return result;
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
    }

    HttpProtocolResult endOfInput() {
        if (failed) throw new IllegalStateException("Decoder failed");
        result = new HttpProtocolResult(result.request(), List.of(), true);
        return result;
    }
    void requireOpen() {
        if (failed || result.endOfInput()) throw new IllegalStateException("Decoder has ended or failed");
    }
    private String readLine(int value) throws IOException {
        if (lineBytes == limits.lineBytes()) throw invalid("HTTP line exceeds byte limit");
        lineBytes++;
        if (carriageReturn) {
            if (value != '\n') throw invalid("CR must be followed by LF");
            String text = line.toString();
            line.setLength(0);
            lineBytes = 0;
            carriageReturn = false;
            return text;
        }
        if (value == '\r') carriageReturn = true;
        else if (value == '\n') throw invalid("Bare LF is not supported");
        else if (value == 127 || (value < 32 && value != '\t')) throw invalid("Control byte in HTTP line");
        else line.append((char) value);
        return null;
    }
    private void parseInitialLine(String text) throws IOException {
        String[] parts = text.split(" ", -1);
        if (parts.length != 3 || !token(parts[0]) || parts[1].isEmpty()
                || parts[1].chars().anyMatch(c -> c < 33 || c > 126)
                || !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0")))
            throw invalid("Invalid or unsupported HTTP request line");
        initialLine = text;
    }
    private void parseField(String text, Map<String, List<String>> fields, boolean trailer) throws IOException {
        int colon = text.indexOf(':');
        if (colon <= 0 || !token(text.substring(0, colon))) throw invalid("Invalid HTTP field name");
        String name = text.substring(0, colon).toLowerCase(Locale.ROOT);
        if (trailer && FORBIDDEN_TRAILERS.contains(name)) throw invalid("Framing/routing field in trailers");
        String value = trimOws(text.substring(colon + 1));
        fields.computeIfAbsent(name, unused -> new ArrayList<>()).add(value);
        if (trailer) frozenTrailers = null;
        else frozenHeaders = null;
    }
    private void finishHeaders() throws IOException {
        headerState = COMPLETE;
        List<String> host = headers.get("host");
        if ((host != null && (host.size() != 1 || host.get(0).isEmpty()))
                || (initialLine.endsWith("HTTP/1.1") && host == null))
            throw invalid("HTTP/1.1 requires exactly one nonempty Host");
        List<String> transfer = headers.get("transfer-encoding");
        List<String> length = headers.get("content-length");
        if (transfer != null) {
            if (length != null) throw invalid("Transfer-Encoding and Content-Length cannot be combined");
            if (!initialLine.endsWith("HTTP/1.1") || transfer.size() != 1
                    || !transfer.get(0).equalsIgnoreCase("chunked"))
                throw invalid("Only a single chunked transfer coding is supported");
            phase = Phase.CHUNK_SIZE;
            return;
        }
        Integer count = null;
        if (length != null) {
            for (String field : length) {
                for (String part : field.split(",", -1)) {
                    int parsed = number(trimOws(part), 10, limits.contentBytes());
                    if (count != null && count != parsed) throw invalid("Conflicting Content-Length values");
                    count = parsed;
                }
            }
        }
        remaining = count == null ? 0 : count;
        trailerState = NOT_APPLICABLE;
        if (remaining == 0) { contentState = NOT_APPLICABLE; phase = Phase.DONE; }
        else phase = Phase.FIXED;
    }
    private void readChunkSize(String text) throws IOException {
        int index = 0;
        while (index < text.length() && hex(text.charAt(index)) >= 0) index++;
        remaining = number(text.substring(0, index), 16, limits.contentBytes() - content.size());
        validateExtensions(text, index);
        if (remaining == 0) { contentState = COMPLETE; phase = Phase.TRAILERS; }
        else phase = Phase.CHUNK_DATA;
    }
    private static void validateExtensions(String text, int index) throws IOException {
        while (index < text.length()) {
            index = skipOws(text, index);
            if (index == text.length() || text.charAt(index++) != ';') throw invalid("Invalid chunk extension");
            index = skipOws(text, index);
            int start = index;
            while (index < text.length() && tokenChar(text.charAt(index))) index++;
            if (start == index) throw invalid("Empty chunk extension name");
            index = skipOws(text, index);
            if (index < text.length() && text.charAt(index) == '=') {
                index = skipOws(text, index + 1);
                if (index < text.length() && text.charAt(index) == '"') {
                    index++;
                    boolean closed = false;
                    while (index < text.length()) {
                        char c = text.charAt(index++);
                        if (c == '"') { closed = true; break; }
                        if (c == '\\') {
                            if (index == text.length()) throw invalid("Incomplete quoted chunk extension");
                            c = text.charAt(index++);
                        }
                        if (c == 127 || (c < 32 && c != '\t')) throw invalid("Invalid chunk extension value");
                    }
                    if (!closed) throw invalid("Unclosed chunk extension");
                } else {
                    start = index;
                    while (index < text.length() && tokenChar(text.charAt(index))) index++;
                    if (start == index) throw invalid("Empty chunk extension value");
                }
            }
        }
    }
    private void countChunkFraming() throws IOException {
        if (++chunkFramingBytes > (long) limits.headerBytes() * 16)
            throw invalid("Chunk framing exceeds byte limit");
    }
    private static int number(String value, int radix, int maximum) throws IOException {
        if (value.isEmpty()) throw invalid("Empty HTTP length");
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = radix == 16 ? hex(value.charAt(i)) : value.charAt(i) - '0';
            if (digit < 0 || digit >= radix || digit > maximum || result > (maximum - digit) / radix)
                throw invalid("Invalid HTTP length or content byte limit exceeded");
            result = result * radix + digit;
        }
        return result;
    }
    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    private static boolean token(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> tokenChar((char) c));
    }
    private static boolean tokenChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
            || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
    private static int skipOws(String text, int index) {
        while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\t')) index++;
        return index;
    }
    private static String trimOws(String text) {
        int start = skipOws(text, 0), end = text.length();
        while (end > start && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) end--;
        return text.substring(start, end);
    }
    private HttpRequestSnapshot snapshot() {
        if (frozenHeaders == null) frozenHeaders = HttpRequestSnapshot.copyFields(headers);
        if (frozenTrailers == null) frozenTrailers = HttpRequestSnapshot.copyFields(trailers);
        return new HttpRequestSnapshot(phase == Phase.INITIAL ? line.toString() : initialLine,
            frozenHeaders, content.snapshot(), frozenTrailers, initialState, headerState, contentState, trailerState);
    }
    private void resetRequest() {
        phase = Phase.INITIAL;
        initialLine = "";
        initialState = headerState = contentState = trailerState = NOT_REACHED;
        headers = new LinkedHashMap<>();
        trailers = new LinkedHashMap<>();
        frozenHeaders = frozenTrailers = Map.of();
        content = new HttpContentBuffer();
        line = new StringBuilder();
        carriageReturn = false;
        lineBytes = headerBytes = trailerBytes = remaining = 0;
        chunkFramingBytes = 0;
    }
    private static IOException invalid(String message) { return new IOException(message); }
}
