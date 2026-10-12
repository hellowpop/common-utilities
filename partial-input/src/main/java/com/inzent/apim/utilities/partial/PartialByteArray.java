package com.inzent.apim.utilities.partial;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * A view of byte-array blocks concatenated in the supplied order, including overlaps.
 * Each packed long contains a nonnegative signed int offset in its high 32 bits
 * and a nonnegative signed int length in its low 32 bits. Empty blocks are allowed.
 * The selection is copied; the source array is shared and must not be modified during reads.
 * This view retains the source for reuse, even after shrink; discard the view when no longer needed.
 */
public final class PartialByteArray {
    private final byte[] source;
    private final PartialBlocks selection;

    /** @throws IllegalArgumentException if a block is negative or outside the source */
    public PartialByteArray(byte[] source, List<Long> partialInfo) {
        this.source = Objects.requireNonNull(source, "source");
        selection = new PartialBlocks(source.length, partialInfo);
    }

    /**
     * Returns a new compact array; neither the original array nor this view is changed.
     * @throws IllegalStateException if the selected length exceeds the Java array index limit
     */
    public byte[] shrink() {
        if (selection.size > Integer.MAX_VALUE) {
            throw new IllegalStateException("Selected data is too large for a byte array: " + selection.size);
        }
        byte[] result = new byte[(int) selection.size];
        int position = 0;
        for (var block : selection.blocks) {
            int count = (int) block.length();
            System.arraycopy(source, (int) block.offset(), result, position, count);
            position += count;
        }
        return result;
    }

    /**
     * Returns an independent stream over the selected blocks. Mark/reset is not supported.
     * Closing the stream releases its source and selection references, but does not close this view.
     */
    public InputStream openStream() {
        return new PartialInputStream(source, null, selection);
    }
}
