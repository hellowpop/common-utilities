package com.inzent.apim.utilities.pojocodec.codec;

import com.inzent.apim.utilities.pojocodec.*;

import java.io.*;
import java.lang.reflect.Field;

public abstract class AbstractFieldCodec {
	public abstract void writeField(OutputStream stream,
	                                Object bean,
	                                Field field,
	                                EncodeContext context) throws IOException;

	public abstract void readField(InputStream stream,
	                               Object bean,
	                               Field field,
	                               DecodeContext context) throws IOException;

	public boolean supportsVersion(int version) {
		return version == 1;
	}
}
