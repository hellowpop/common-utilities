package com.inzent.apim.utilities.pojocodec.codec;
import com.inzent.apim.utilities.pojocodec.*;
import java.io.*;
public class DefaultTypeCodec<T> extends AbstractTypeCodec<T> {
    @Override public void writeBody(OutputStream stream, T bean, EncodeContext context) throws IOException { context.writeDefaultPojo(stream, bean); }
    @Override public T readBody(InputStream stream, Class<T> type, DecodeContext context) throws IOException { return type.cast(context.readDefaultPojo(stream, type)); }
}
