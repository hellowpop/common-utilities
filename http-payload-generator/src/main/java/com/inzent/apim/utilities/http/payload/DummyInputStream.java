package com.inzent.apim.utilities.http.payload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Locale;

/** A segment-at-a-time generator; memory use does not depend on payload or value size. */
final class DummyInputStream extends InputStream {
    private static final String VALUE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final ContentType type;
    private final long blockSize;
    private long bodyRemaining, index, repeated;
    private byte[] literal = new byte[0];
    private int position, stage;
    private byte repeatedByte;
    private boolean sequenceValue;
    private int valuePosition;
    private String pendingEnd = "";
    private boolean closed;

    DummyInputStream(ContentType type, long length, long blockSize) {
        this.type = type;
        this.blockSize = blockSize;
        bodyRemaining = length == 0 ? 0 : length - (type == ContentType.MULTIPART_FORM_DATA ? type.close.length() : type.minimumLength());
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
                    boolean multipart = type == ContentType.MULTIPART_FORM_DATA;
                    String start = multipart ? ContentType.multipartHeader(index)
                        : (index == 0 ? "" : type.separator) + String.format(Locale.ROOT, type.key, index);
                    String end = String.format(Locale.ROOT, type.end, index);
                    long overhead = start.length() + end.length();
                    if (bodyRemaining < overhead || (overhead == 0 && type != ContentType.APPLICATION_OCTET_STREAM)) {
                        if (multipart) { stage = 4; continue; }
                        sequenceValue = false;
                        repeatedByte = (byte) type.padding;
                        repeated = bodyRemaining;
                        bodyRemaining = 0;
                        stage = 4;
                    } else {
                        long available = bodyRemaining - overhead;
                        // Reserve the mandatory file part and share available data between the first two parts.
                        if (multipart && index == 0) {
                            available -= ContentType.multipartHeader(1).length() + 2;
                            available = available / 2 + available % 2;
                        }
                        long valueLength = Math.min(blockSize, available);
                        bodyRemaining -= overhead + valueLength;
                        literal(start);
                        sequenceValue = true;
                        repeated = valueLength;
                        pendingEnd = end;
                        index++;
                        stage = 2;
                    }
                }
                case 2 -> { literal(pendingEnd); stage = 1; }
                case 4 -> { literal(type.close); stage = type == ContentType.MULTIPART_FORM_DATA ? 6 : 5; }
                case 6 -> {
                    // MIME epilogue preserves exact length without exceeding any part's blockSize.
                    sequenceValue = false;
                    repeatedByte = ' ';
                    repeated = bodyRemaining;
                    bodyRemaining = 0;
                    stage = 5;
                }
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
        return sequenceValue ? nextValueByte() : repeatedByte & 0xff;
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
                if (sequenceValue) {
                    for (int i = offset + written; i < offset + written + count; i++) bytes[i] = nextValueByte();
                } else {
                    Arrays.fill(bytes, offset + written, offset + written + count, repeatedByte);
                }
                repeated -= count;
            }
            written += count;
        }
        return written == 0 ? -1 : written;
    }

    @Override public void close() { closed = true; }

    private byte nextValueByte() {
        byte value = (byte) VALUE_ALPHABET.charAt(valuePosition);
        valuePosition = (valuePosition + 1) % VALUE_ALPHABET.length();
        return value;
    }
}
