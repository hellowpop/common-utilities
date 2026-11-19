package com.inzent.apim.utilities.pojocodec.codec;
import com.inzent.apim.utilities.pojocodec.*;
import java.io.*;
import java.lang.reflect.Field;
public class DefaultFieldCodec extends AbstractFieldCodec {
    @Override public void writeField(OutputStream stream, Object bean, Field field, EncodeContext context) throws IOException { context.writeDefaultField(stream, bean, field); }
    @Override public void readField(InputStream stream, Object bean, Field field, DecodeContext context) throws IOException { context.readDefaultField(stream, bean, field); }
}
