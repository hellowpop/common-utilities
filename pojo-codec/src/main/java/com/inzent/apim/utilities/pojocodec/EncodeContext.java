package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.internal.*;

import java.io.*;
import java.lang.reflect.*;

/**
 * Call-scoped extension context. Do not retain it after a Codec callback.
 */
public final class EncodeContext {
	public final Engine engine;
	public final Session session;
	private final Type type;
	private final CodecOptions options;
	private final int codecVersion;

	public EncodeContext(Engine engine,
	                     Session session,
	                     Type type,
	                     CodecOptions options,
	                     int codecVersion) {
		this.engine = engine;
		this.session = session;
		this.type = type;
		this.options = options;
		this.codecVersion = codecVersion;
	}

	public Type type() {
		return this.type;
	}

	public CodecOptions options() {
		return this.options;
	}

	public int codecVersion() {
		return this.codecVersion;
	}

	public void writeValue(OutputStream stream,
	                       Object value,
	                       Type declared) throws IOException {
		this.engine.writeValue(stream,
		                       value,
		                       declared,
		                       this,
		                       false);
	}

	public void writeDefaultPojo(OutputStream stream,
	                             Object value) throws IOException {
		this.engine.writePojoBody(stream,
		                          value,
		                          this);
	}

	public void writeDefaultField(OutputStream stream,
	                              Object bean,
	                              Field field) throws IOException {
		try {
			this.writeValue(stream,
			                field.get(bean),
			                this.type);
		}
		catch (IllegalAccessException exception) {
			throw this.session.error("Field access: " + field,
			                         exception);
		}
	}

	public EncodeContext with(Type declared,
	                          CodecOptions effective,
	                          int version) {
		return new EncodeContext(this.engine,
		                         this.session,
		                         declared,
		                         effective,
		                         version);
	}
}
