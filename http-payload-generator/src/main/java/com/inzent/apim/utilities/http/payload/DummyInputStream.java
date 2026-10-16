package com.inzent.apim.utilities.http.payload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Locale;

/** A segment-at-a-time generator; memory use does not depend on payload or value size. */
final class DummyInputStream extends InputStream {
    private final ContentType type;
    private final long blockSize;
    private long bodyRemaining, index, repeated;
    private byte[] literal = new byte[0];
    private int position, stage;
    private byte repeatedByte = 'a';
    private String pendingEnd = "";
    private boolean closed;

    DummyInputStream(ContentType type, long length, long blockSize) {
        this.type = type;
        this.blockSize = blockSize;
        bodyRemaining = length == 0 ? 0 : length - type.minimumLength();
        stage = length == 0 ? 5 : 0;
    }

    private void literal(String value) {
        literal = value.getBytes(StandardCharsets.US_ASCII);
        position = 0;
    }

    private boolean next() {
        while (position == literal.length && repeated == 0) {
            switch (stage) {
                case 0 -> { literal(type.open); stage = 1; }
                case 1 -> {
                    if (bodyRemaining == 0) { stage = 4; continue; }
                    String start = (index == 0 ? "" : type.separator) + String.format(Locale.ROOT, type.key, index);
                    String end = String.format(Locale.ROOT, type.end, index);
                    long overhead = start.length() + end.length();
                    if (bodyRemaining < overhead || (overhead == 0 && type != ContentType.APPLICATION_OCTET_STREAM)) {
                        repeatedByte = (byte) type.padding;
                        repeated = bodyRemaining;
                        bodyRemaining = 0;
                        stage = 4;
                    } else {
                        long valueLength = Math.min(blockSize, bodyRemaining - overhead);
                        bodyRemaining -= overhead + valueLength;
                        literal(start);
                        repeatedByte = 'a';
                        repeated = valueLength;
                        pendingEnd = end;
                        index++;
                        stage = 2;
                    }
                }
                case 2 -> { literal(pendingEnd); stage = 1; }
                case 4 -> { literal(type.close); stage = 5; }
                default -> { return false; }
            }
        }
        return true;
    }

    @Override public int read() throws IOException {
        if (closed) throw new IOException("stream is closed");
        if (!next()) return -1;
        if (position < literal.length) return literal[position++] & 0xff;
        repeated--;
        return repeatedByte & 0xff;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (closed) throw new IOException("stream is closed");
        if (length == 0) return 0;
        int written = 0;
        while (written < length && next()) {
            int count;
            if (position < literal.length) {
                count = Math.min(length - written, literal.length - position);
                System.arraycopy(literal, position, bytes, offset + written, count);
                position += count;
            } else {
                count = (int) Math.min(length - written, repeated);
                Arrays.fill(bytes, offset + written, offset + written + count, repeatedByte);
                repeated -= count;
            }
            written += count;
        }
        return written == 0 ? -1 : written;
    }

    @Override public void close() { closed = true; }
}
