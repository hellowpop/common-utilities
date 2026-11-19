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
		active.clear();
		depth = 0;
		items = 0;
		buffers = 0;
		path = "root";
		rootDeserializedBean = null;
		rootDeserializeFailed = false;
		currentDeserializingBean = null;
	}

	public void enter(Object value) throws PojoCodecException {
		if (depth >= limits.depth()) {
			throw error("Maximum depth exceeded");
		}
		if (value != null && active.containsKey(value)) {
			throw error("Cyclic object graph");
		}
		depth++;
		if (value != null) {
			active.put(value,
			           Boolean.TRUE);
		}
	}

	public void leave(Object value) {
		depth--;
		if (value != null) {
			active.remove(value);
		}
	}

	public void items(int count) throws PojoCodecException {
		if (count < 0 || count > limits.containerItems() || items > limits.totalItems() - count) {
			throw error("Item budget exceeded");
		}
		items += count;
	}

	public void reserve(int bytes) throws PojoCodecException {
		if (bytes < 0 || buffers > limits.bufferBytes() - bytes) {
			throw error("Active buffer budget exceeded");
		}
		buffers += bytes;
	}

	public void release(int bytes) {
		buffers -= bytes;
	}

	public String push(String segment) {
		String previous = path;
		path += segment;
		return previous;
	}

	public void restore(String previous) {
		path = previous;
	}

	public PojoCodecException error(String message) {
		return new PojoCodecException(path + ": " + message);
	}

	public PojoCodecException error(String message,
	                                Throwable cause) {
		return new PojoCodecException(path + ": " + message,
		                              cause);
	}
}
