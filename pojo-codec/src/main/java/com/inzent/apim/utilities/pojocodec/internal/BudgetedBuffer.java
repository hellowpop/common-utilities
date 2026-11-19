package com.inzent.apim.utilities.pojocodec.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Charges the buffer capacity, not only its current payload.
 */
public final class BudgetedBuffer
		extends OutputStream {
	private final Session session;
	private final int maximum;
	private byte[] bytes = new byte[0];
	private int size;
	private boolean closed;

	public BudgetedBuffer(Session session,
	                      int maximum) {
		this.session = session;
		this.maximum = maximum;
	}

	private void grow(int count) throws IOException {
		if (this.closed || count < 0 || count > this.maximum - this.size) {
			throw this.session.error("Buffer/frame size exceeded");
		}
		int required = this.size + count;
		if (required > this.bytes.length) {
			int capacity = Math.min(this.maximum,
			                        Math.max(required,
			                                 (int) Math.min(Integer.MAX_VALUE,
			                                                Math.max(32L,
			                                                         this.bytes.length * 2L))));
			// Transient old+new storage is charged while copying.
			this.session.reserve(capacity);
			try {
				byte[] replacement = java.util.Arrays.copyOf(this.bytes,
				                                             capacity);
				this.session.release(this.bytes.length);
				this.bytes = replacement;
			}
			catch (Throwable failure) {
				this.session.release(capacity);
				throw failure;
			}
		}
	}

	@Override
	public void write(int value) throws IOException {
		this.grow(1);
		this.bytes[this.size++] = (byte) value;
	}

	@Override
	public void write(byte[] value,
	                  int off,
	                  int count) throws IOException {
		Objects.checkFromIndexSize(off,
		                           count,
		                           value.length);
		this.grow(count);
		System.arraycopy(value,
		                 off,
		                 this.bytes,
		                 this.size,
		                 count);
		this.size += count;
	}

	public int size() {
		return this.size;
	}

	public void writeTo(OutputStream out) throws IOException {
		out.write(this.bytes,
		          0,
		          this.size);
	}

	public java.io.InputStream input() {
		return new java.io.ByteArrayInputStream(this.bytes,
		                                        0,
		                                        this.size);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.session.release(this.bytes.length);
			this.bytes = new byte[0];
			this.size = 0;
			this.closed = true;
		}
	}
}
