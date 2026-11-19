package com.inzent.apim.utilities.pojocodec.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Charges the buffer capacity, not only its current payload. */
public final class BudgetedBuffer extends OutputStream {
    private final Session session;
    private final int maximum;
    private byte[] bytes = new byte[0];
    private int size;
    private boolean closed;
    public BudgetedBuffer(Session session, int maximum) { this.session = session; this.maximum = maximum; }
    private void grow(int count) throws IOException {
        if (closed || count < 0 || count > maximum - size) { throw session.error("Buffer/frame size exceeded"); }
        int required = size + count;
        if (required > bytes.length) {
            int capacity = Math.min(maximum, Math.max(required, (int) Math.min(Integer.MAX_VALUE, Math.max(32L, bytes.length * 2L))));
            // Transient old+new storage is charged while copying.
            session.reserve(capacity);
            try { byte[] replacement = java.util.Arrays.copyOf(bytes, capacity); session.release(bytes.length); bytes = replacement; }
            catch (Throwable failure) { session.release(capacity); throw failure; }
        }
    }
    @Override public void write(int value) throws IOException { grow(1); bytes[size++] = (byte) value; }
    @Override public void write(byte[] value, int off, int count) throws IOException {
        Objects.checkFromIndexSize(off, count, value.length); grow(count); System.arraycopy(value, off, bytes, size, count); size += count;
    }
    public int size() { return size; }
    public void writeTo(OutputStream out) throws IOException { out.write(bytes, 0, size); }
    public java.io.InputStream input() { return new java.io.ByteArrayInputStream(bytes, 0, size); }
    @Override public void close() { if (!closed) { session.release(bytes.length); bytes = new byte[0]; size = 0; closed = true; } }
}
