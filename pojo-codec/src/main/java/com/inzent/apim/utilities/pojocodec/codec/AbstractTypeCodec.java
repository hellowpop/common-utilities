package com.inzent.apim.utilities.pojocodec.codec;

import com.inzent.apim.utilities.pojocodec.*;

import java.io.*;

public abstract class AbstractTypeCodec<T> {
	public abstract void writeBody(OutputStream stream,
	                               T bean,
	                               EncodeContext context) throws IOException;

	public T readBody(InputStream stream,
	                  Class<T> type,
	                  DecodeContext context) throws IOException {
		T bean = this.createInstance(type,
		                             context);
		this.readInto(stream,
		              bean,
		              context);
		return bean;
	}

	/**
	 * Override for custom construction; do not consume bean fields before the lifecycle hook.
	 */
	public T createInstance(Class<T> type,
	                        DecodeContext context) throws IOException {
		return type.cast(context.createDefaultPojo(type));
	}

	/**
	 * Injectable types are populated into this exact, already-created instance.
	 */
	public void readInto(InputStream stream,
	                     T bean,
	                     DecodeContext context) throws IOException {
		throw new PojoCodecException("Injectable/custom Codec must implement readInto: " + this.getClass()
		                                                                                       .getName());
	}

	public boolean supportsVersion(int version) {
		return version == 1;
	}
}
