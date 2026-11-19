package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.internal.*;
import java.io.*;
import java.lang.reflect.*;

/** Call-scoped extension context. Do not retain it after a Codec callback. */
public final class EncodeContext {
    public final Engine engine;
    public final Session session;
    private final Type type;
    private final CodecOptions options;
    private final int codecVersion;
    public EncodeContext(Engine engine, Session session, Type type, CodecOptions options, int codecVersion) {
        this.engine = engine; this.session = session; this.type = type; this.options = options; this.codecVersion = codecVersion;
    }
    public Type type() { return type; }
    public CodecOptions options() { return options; }
    public int codecVersion() { return codecVersion; }
    public void writeValue(OutputStream stream, Object value, Type declared) throws IOException { engine.writeValue(stream, value, declared, this, false); }
    public void writeDefaultPojo(OutputStream stream, Object value) throws IOException { engine.writePojoBody(stream, value, this); }
    public void writeDefaultField(OutputStream stream, Object bean, Field field) throws IOException {
        try { writeValue(stream, field.get(bean), type); }
        catch (IllegalAccessException exception) { throw session.error("Field access: " + field, exception); }
    }
    public EncodeContext with(Type declared, CodecOptions effective, int version) { return new EncodeContext(engine, session, declared, effective, version); }
}
