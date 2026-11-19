package com.inzent.apim.utilities.pojocodec.codec;

import com.inzent.apim.utilities.pojocodec.*;

import java.io.*;

public class DefaultTypeCodec<T>
		extends AbstractTypeCodec<T> {
	@Override
	public void writeBody(OutputStream stream,
	                      T bean,
	                      EncodeContext context) throws IOException {
		context.writeDefaultPojo(stream,
		                         bean);
	}

	@Override
	public T readBody(InputStream stream,
	                  Class<T> type,
	                  DecodeContext context) throws IOException {
		return type.cast(context.readDefaultPojo(stream,
		                                         type));
	}

	@Override
	public void readInto(InputStream stream,
	                     T bean,
	                     DecodeContext context) throws IOException {
		if (this.getClass() != DefaultTypeCodec.class) {
			try {
				if (this.getClass()
				        .getMethod("readBody",
				                   InputStream.class,
				                   Class.class,
				                   DecodeContext.class)
				        .getDeclaringClass() != DefaultTypeCodec.class) {
					throw new PojoCodecException("Custom readBody requires an explicit readInto override for injectable types: " + this.getClass()
					                                                                                                                   .getName());
				}
			}
			catch (NoSuchMethodException exception) {
				throw new PojoCodecException("Cannot inspect custom Type Codec",
				                             exception);
			}
		}
		context.readDefaultPojoInto(stream,
		                            bean);
	}
}
