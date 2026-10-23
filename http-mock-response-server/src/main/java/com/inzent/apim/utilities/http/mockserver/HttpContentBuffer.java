package com.inzent.apim.utilities.http.mockserver;

import java.util.Arrays;

/** Append-only blocks. Snapshots share frozen blocks and copy at most one 8 KiB tail. */
final class HttpContentBuffer {
    private static final int BLOCK_SIZE = 8192;
    private static final byte[] EMPTY = new byte[0];
    private record Block(Block previous, byte[] bytes) {}
    private Block head;
    private byte[] tail;
    private int tailSize;
    private int size;

    void write(int value) {
        if (tail == null) tail = new byte[BLOCK_SIZE];
        tail[tailSize++] = (byte) value;
        size++;
        if (tailSize == BLOCK_SIZE) {
            head = new Block(head, tail);
            tail = null;
            tailSize = 0;
        }
    }
    int size() { return size; }
    Snapshot snapshot() {
        return new Snapshot(head, tailSize == 0 ? EMPTY : Arrays.copyOf(tail, tailSize), size);
    }
    static Snapshot copyOf(byte[] bytes) { return new Snapshot(null, bytes.clone(), bytes.length); }

    static final class Snapshot {
        private final Block head;
        private final byte[] tail;
        private final int size;
        private Snapshot(Block head, byte[] tail, int size) {
            this.head = head; this.tail = tail; this.size = size;
        }
        byte[] bytes() {
            byte[] result = new byte[size];
            int offset = size - tail.length;
            System.arraycopy(tail, 0, result, offset, tail.length);
            for (Block block = head; block != null; block = block.previous()) {
                offset -= block.bytes().length;
                System.arraycopy(block.bytes(), 0, result, offset, block.bytes().length);
            }
            return result;
        }
        int size() { return size; }
    }
}
