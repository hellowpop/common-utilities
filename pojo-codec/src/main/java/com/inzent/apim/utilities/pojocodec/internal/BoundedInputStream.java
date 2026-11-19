package com.inzent.apim.utilities.pojocodec.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class BoundedInputStream
		extends InputStream {
	private final InputStream parent;
	private int remaining;

	public BoundedInputStream(InputStream parent,
	                          int size) {
		if (size < 0) {
			throw new IllegalArgumentException("Negative length");
		}
		this.parent = Objects.requireNonNull(parent);
		this.remaining = size;
	}

	@Override
	public int read() throws IOException {
		if (this.remaining == 0) {
			return -1;
		}
		int value = this.parent.read();
		if (value < 0) {
			throw new IOException("Truncated frame");
		}
		this.remaining--;
		return value;
	}

	@Override
	public int read(byte[] bytes,
	                int off,
	                int len) throws IOException {
		Objects.checkFromIndexSize(off,
		                           len,
		                           bytes.length);
		if (len == 0) {
			return 0;
		}
		if (this.remaining == 0) {
			return -1;
		}
		int count = this.parent.read(bytes,
		                             off,
		                             Math.min(this.remaining,
		                                      len));
		if (count < 0) {
			throw new IOException("Truncated frame");
		}
		if (count == 0) {
			bytes[off] = (byte) this.read();
			return 1;
		}
		this.remaining -= count;
		return count;
	}

	@Override
	public long skip(long size) throws IOException {
		long skipped = this.parent.skip(Math.min(this.remaining,
		                                         Math.max(0,
		                                                  size)));
		this.remaining -= (int) skipped;
		return skipped;
	}

	@Override
	public void close() { /* Caller owns parent. */ }
}
