package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.internal.*;
import java.io.*;
import java.lang.reflect.*;

public final class DecodeContext {
    public final Engine engine;
    public final Session session;
    private final Type type;
    private final CodecOptions options;
    private final int codecVersion;
    public DecodeContext(Engine engine, Session session, Type type, CodecOptions options, int codecVersion) {
        this.engine = engine; this.session = session; this.type = type; this.options = options; this.codecVersion = codecVersion;
    }
    public Type type() { return type; }
    public CodecOptions options() { return options; }
    public int codecVersion() { return codecVersion; }
    public Object readValue(InputStream stream, Type declared) throws IOException { return engine.readValue(stream, declared, this); }
    public Object readDefaultPojo(InputStream stream, Class<?> declared) throws IOException { return engine.readPojoBody(stream, declared, this); }
    public void readDefaultField(InputStream stream, Object bean, Field field) throws IOException {
        try { field.set(bean, readValue(stream, type)); }
        catch (IllegalAccessException | IllegalArgumentException exception) { throw session.error("Field assignment: " + field, exception); }
    }
    public DecodeContext with(Type declared, CodecOptions effective, int version) { return new DecodeContext(engine, session, declared, effective, version); }
}
