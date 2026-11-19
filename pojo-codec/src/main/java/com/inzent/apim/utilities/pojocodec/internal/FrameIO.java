package com.inzent.apim.utilities.pojocodec.internal;

import com.inzent.apim.utilities.pojocodec.PojoCodecException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class FrameIO {
    private FrameIO() { }
    public static int length(InputStream stream, int maximum) throws IOException {
        int value = new DataInputStream(stream).readInt();
        if (value < 0 || value > maximum) { throw new PojoCodecException("Invalid length: " + value); }
        return value;
    }
    public static byte[] readExact(InputStream stream, int size) throws IOException {
        if (size < 0) { throw new PojoCodecException("Negative length"); }
        byte[] bytes = new byte[size];
        new DataInputStream(stream).readFully(bytes);
        return bytes;
    }
    public static void skipExact(InputStream stream, int size) throws IOException {
        if (size < 0) { throw new PojoCodecException("Negative length"); }
        int remaining = size;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped > 0) { remaining -= (int) skipped; }
            else if (stream.read() < 0) { throw new PojoCodecException("Truncated frame"); }
            else { remaining--; }
        }
    }
    public static void writeString(OutputStream stream, String value) throws IOException {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException exception) { throw new PojoCodecException("Invalid UTF-16 string", exception); }
        new DataOutputStream(stream).writeInt(bytes.length);
        stream.write(bytes);
    }
    public static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { throw new PojoCodecException("Invalid UTF-8", exception); }
    }
    public static String readString(InputStream stream, int maximum) throws IOException {
        return decodeUtf8(readExact(stream, length(stream, maximum)));
    }
    public static void exhausted(InputStream stream) throws IOException {
        if (stream.read() != -1) { throw new PojoCodecException("Unconsumed frame bytes"); }
    }
}
