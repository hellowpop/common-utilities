package com.inzent.apim.utilities.pojocodec.internal;

import com.inzent.apim.utilities.pojocodec.CodecLimits;
import com.inzent.apim.utilities.pojocodec.PojoCodecException;

import java.util.IdentityHashMap;

/**
 * Mutable state belongs to exactly one operation.
 */
public final class Session {
	public final CodecLimits limits;
	private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
	private int depth;
	private long items;
	private long buffers;
	private String path = "root";
	public Object rootDeserializedBean;
	public boolean rootDeserializeFailed;
	public Object currentDeserializingBean;

	public Session(CodecLimits limits) {
		this.limits = limits;
	}

	public void reset() {
		this.active.clear();
		this.depth = 0;
		this.items = 0;
		this.buffers = 0;
		this.path = "root";
		this.rootDeserializedBean = null;
		this.rootDeserializeFailed = false;
		this.currentDeserializingBean = null;
	}

	public void enter(Object value) throws PojoCodecException {
		if (this.depth >= this.limits.depth()) {
			throw this.error("Maximum depth exceeded");
		}
		if (value != null && this.active.containsKey(value)) {
			throw this.error("Cyclic object graph");
		}
		this.depth++;
		if (value != null) {
			this.active.put(value,
			                Boolean.TRUE);
		}
	}

	public void leave(Object value) {
		this.depth--;
		if (value != null) {
			this.active.remove(value);
		}
	}

	public void items(int count) throws PojoCodecException {
		if (count < 0 || count > this.limits.containerItems() || this.items > this.limits.totalItems() - count) {
			throw this.error("Item budget exceeded");
		}
		this.items += count;
	}

	public void reserve(int bytes) throws PojoCodecException {
		if (bytes < 0 || this.buffers > this.limits.bufferBytes() - bytes) {
			throw this.error("Active buffer budget exceeded");
		}
		this.buffers += bytes;
	}

	public void release(int bytes) {
		this.buffers -= bytes;
	}

	public String push(String segment) {
		String previous = this.path;
		this.path += segment;
		return previous;
	}

	public void restore(String previous) {
		this.path = previous;
	}

	public PojoCodecException error(String message) {
		return new PojoCodecException(this.path + ": " + message);
	}

	public PojoCodecException error(String message,
	                                Throwable cause) {
		return new PojoCodecException(this.path + ": " + message,
		                              cause);
	}
}
