package com.inzent.apim.utilities.partial;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validated, immutable selection shared by the array and file implementations. */
final class PartialBlocks {
    record Block(long offset, long length) { }

    final List<Block> blocks;
    final long size;

    PartialBlocks(long sourceSize, List<Long> partialInfo) {
        Objects.requireNonNull(partialInfo, "partialInfo");
        var selected = new ArrayList<Block>(partialInfo.size());
        long total = 0;
        int index = 0;
        for (Long value : partialInfo) {
            long packed = Objects.requireNonNull(value, "partialInfo[" + index + "]");
            int offset = (int) (packed >>> 32);
            int length = (int) packed;
            if (offset < 0 || length < 0 || (long) offset + length > sourceSize) {
                throw new IllegalArgumentException("Invalid partialInfo[" + index
                        + "]: offset=" + offset + ", length=" + length + ", sourceSize=" + sourceSize);
            }
            if (length != 0) selected.add(new Block(offset, length));
            total += length;
            index++;
        }
        blocks = List.copyOf(selected);
        size = total;
    }

    private PartialBlocks(long size) {
        this.size = size;
        blocks = size == 0 ? List.of() : List.of(new Block(0, size));
    }

    PartialBlocks compacted() {
        return new PartialBlocks(size);
    }
}
