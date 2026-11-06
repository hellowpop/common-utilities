package com.inzent.apim.utilities.http.snappy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorInputStream;
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorOutputStream;
import org.apache.commons.compress.compressors.snappy.SnappyCompressorInputStream;
import org.apache.commons.compress.compressors.snappy.SnappyCompressorOutputStream;

/** Snappy examples. Each method owns and closes both supplied streams. */
public final class SnappyStreamExample {
    private SnappyStreamExample() { }

    /** Compresses one raw block; size must equal the number of source bytes. */
    public static void compress(InputStream source, OutputStream target, long size) throws IOException {
        if (size < 0 || size > 0xffff_ffffL) {
            throw new IllegalArgumentException("Raw Snappy size must be between 0 and 4294967295");
        }
        try (InputStream in = source; OutputStream destination = target;
             SnappyCompressorOutputStream out = new SnappyCompressorOutputStream(destination, size)) {
            long actualSize = in.transferTo(out);
            if (actualSize != size) {
                throw new IOException("Source size changed: expected " + size + ", actual " + actualSize);
            }
        }
    }

    /** Decompresses a raw Snappy block. */
    public static void decompress(InputStream source, OutputStream target) throws IOException {
        try (InputStream input = source; OutputStream out = target;
             SnappyCompressorInputStream in = new SnappyCompressorInputStream(input)) {
            in.transferTo(out);
        }
    }

    /** Compresses framed Snappy without requiring the source size in advance. */
    public static void compressFramed(InputStream source, OutputStream target) throws IOException {
        try (InputStream in = source; OutputStream destination = target;
             FramedSnappyCompressorOutputStream out = new FramedSnappyCompressorOutputStream(destination)) {
            in.transferTo(out);
        }
    }

    /** Decompresses framed Snappy. */
    public static void decompressFramed(InputStream source, OutputStream target) throws IOException {
        try (InputStream input = source; OutputStream out = target;
             FramedSnappyCompressorInputStream in = new FramedSnappyCompressorInputStream(input)) {
            in.transferTo(out);
        }
    }
}
