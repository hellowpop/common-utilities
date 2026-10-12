package com.inzent.apim.utilities.partial;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

/** Each instance owns its cursor and, for files, its channel. Not thread-safe. */
final class PartialInputStream extends InputStream {
    private byte[] source;
    private FileChannel channel;
    private PartialBlocks selection;
    private final byte[] singleByte = new byte[1];
    private int blockIndex;
    private long blockPosition;
    private long remaining;
    private boolean closed;

    PartialInputStream(byte[] source, FileChannel channel, PartialBlocks selection) {
        this.source = source;
        this.channel = channel;
        this.selection = selection;
        remaining = selection.size;
    }

    @Override public int read() throws IOException {
        return read(singleByte, 0, 1) == -1 ? -1 : singleByte[0] & 0xff;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.length);
        ensureOpen();
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int total = 0;
        while (total < length && remaining > 0) {
            var block = selection.blocks.get(blockIndex);
            int count = (int) Math.min(length - total, block.length() - blockPosition);
            long position = block.offset() + blockPosition;
            if (source != null) {
                System.arraycopy(source, (int) position, buffer, offset + total, count);
            } else {
                count = channel.read(ByteBuffer.wrap(buffer, offset + total, count), position);
                if (count < 0) throw new EOFException("Source ended inside selected block at " + position);
                if (count == 0) throw new IOException("File read made no progress at " + position);
            }
            advance(count);
            total += count;
        }
        return total;
    }

    @Override public long skip(long count) throws IOException {
        ensureOpen();
        long skipped = Math.min(Math.max(count, 0), remaining);
        long left = skipped;
        while (left > 0) {
            long step = Math.min(left, selection.blocks.get(blockIndex).length() - blockPosition);
            advance(step);
            left -= step;
        }
        return skipped;
    }

    @Override public int available() throws IOException {
        ensureOpen();
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }

    @Override public void close() throws IOException {
        if (!closed) {
            closed = true;
            FileChannel ownedChannel = channel;
            source = null;
            selection = null;
            channel = null;
            remaining = 0;
            if (ownedChannel != null) ownedChannel.close();
        }
    }

    private void advance(long count) {
        remaining -= count;
        blockPosition += count;
        if (blockPosition == selection.blocks.get(blockIndex).length()) {
            blockIndex++;
            blockPosition = 0;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Stream is closed");
    }
}
