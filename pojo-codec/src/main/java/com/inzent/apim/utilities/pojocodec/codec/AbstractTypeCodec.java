package com.inzent.apim.utilities.pojocodec.codec;
import com.inzent.apim.utilities.pojocodec.*;
import java.io.*;
public abstract class AbstractTypeCodec<T> {
    public abstract void writeBody(OutputStream stream, T bean, EncodeContext context) throws IOException;
    public abstract T readBody(InputStream stream, Class<T> type, DecodeContext context) throws IOException;
    public boolean supportsVersion(int version) { return version == 1; }
}
