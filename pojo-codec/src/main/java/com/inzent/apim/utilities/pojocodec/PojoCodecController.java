package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.internal.*;
import com.inzent.apim.utilities.pojocodec.registry.*;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Immutable, thread-safe facade. Each invocation owns a separate context.
 */
public final class PojoCodecController {
	private final Engine engine;
	private final CompiledCodec compiled;

	public PojoCodecController() {
		this(new Builder());
	}

	private PojoCodecController(Builder builder) {
		this.engine = new Engine(builder.options,
		                         builder.limits,
		                         new TypeRegistry(builder.types),
		                         new CodecRegistry(builder.codecs),
		                         new ComparatorRegistry(builder.comparators),
		                         new ContainerPolicyRegistry(builder.policies));
		this.compiled = new CompiledCodec(this.engine);
		try {
			for (Class<?> type : builder.types.values()) {
				this.engine.prepare(type);
			}
			for (ContainerPolicyRegistry.Policy policy : builder.policies.values()) {
				if (policy.enumType() != null) {
					this.engine.prepare(policy.enumType());
				}
				if (policy.checkedKeyType() != null) {
					this.engine.prepare(policy.checkedKeyType());
				}
				if (policy.checkedValueType() != null) {
					this.engine.prepare(policy.checkedValueType());
				}
			}
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("Invalid registered type",
			                                   exception);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public void serialize(OutputStream stream,
	                      Object bean) throws IOException {
		if (bean == null) {
			throw new PojoCodecException("Cannot infer null root type");
		}
		this.serializeRoot(stream,
		                   bean.getClass(),
		                   bean);
	}

	public <T> void serialize(OutputStream stream,
	                          Class<T> type,
	                          T bean) throws IOException {
		this.serializeRoot(stream,
		                   type,
		                   bean);
	}

	private void serializeRoot(OutputStream stream,
	                           Class<?> type,
	                           Object bean) throws IOException {
		Objects.requireNonNull(stream);
		Objects.requireNonNull(type);
		if (bean instanceof PojoInjectable && !type.isInstance(bean)) {
			throw new PojoCodecException("Root bean does not match declared type");
		}
		try {
			Lifecycle.beforeSerialize(bean);
			this.serializeDocument(stream,
			                       type,
			                       bean);
			Lifecycle.afterSerialize(bean);
		}
		catch (Throwable cause) {
			Lifecycle.serializeFailed(bean,
			                          cause);
			throw Lifecycle.propagate(cause);
		}
	}

	private void serializeDocument(OutputStream stream,
	                               Class<?> type,
	                               Object bean) throws IOException {
		if (this.compiled.write(stream,
		                        type,
		                        bean)) {
			return;
		}
		this.engine.prepare(type);
		Session session = new Session(this.engine.limits);
		EncodeContext context = new EncodeContext(this.engine,
		                                          session,
		                                          type,
		                                          this.engine.options,
		                                          1);
		try (BudgetedBuffer root = new BudgetedBuffer(session,
		                                              Math.max(0,
		                                                       this.engine.limits.documentBytes() - 12))) {
			this.engine.writeValue(root,
			                       bean,
			                       type,
			                       context,
			                       true);
			DataOutputStream out = new DataOutputStream(stream);
			out.writeInt(0x504a4344);
			out.writeShort(1);
			out.writeShort(0);
			out.writeInt(root.size());
			root.writeTo(out);
		}
	}

	public <T> T deserialize(InputStream stream,
	                         Class<T> type) throws IOException {
		return this.deserializeDocument(stream,
		                                type,
		                                false);
	}

	private <T> T deserializeDocument(InputStream stream,
	                                  Class<T> type,
	                                  boolean strict) throws IOException {
		Objects.requireNonNull(stream);
		Objects.requireNonNull(type);
		if (type.isPrimitive()) {
			throw new PojoCodecException("Root type must be a reference class");
		}
		if (this.compiled.supports(type)) {
			T result = type.cast(this.compiled.read(stream,
			                                        type));
			if (strict) {
				FrameIO.exhausted(stream);
			}
			return result;
		}
		Session session = new Session(this.engine.limits);
		try {
			this.engine.prepare(type);
			DataInputStream in = new DataInputStream(stream);
			if (in.readInt() != 0x504a4344 || in.readUnsignedShort() != 1 || in.readUnsignedShort() != 0) {
				throw session.error("Invalid document header/version/flags");
			}
			BoundedInputStream root = new BoundedInputStream(in,
			                                                 FrameIO.length(in,
			                                                                Math.max(0,
			                                                                         this.engine.limits.documentBytes() - 12)));
			Object value = this.engine.readValue(root,
			                                     type,
			                                     new DecodeContext(this.engine,
			                                                       session,
			                                                       type,
			                                                       this.engine.options,
			                                                       1),
			                                     true);
			FrameIO.exhausted(root);
			if (strict) {
				FrameIO.exhausted(stream);
			}
			Lifecycle.afterDeserialize(session.rootDeserializedBean);
			return type.cast(value);
		}
		catch (Throwable cause) {
			Lifecycle.rootDeserializeFailed(session,
			                                cause);
			if (cause instanceof PojoCodecException) {
				throw (PojoCodecException) cause;
			}
			if (cause instanceof IOException exception) {
				throw session.error("Cannot decode document",
				                    exception);
			}
			throw Lifecycle.propagate(cause);
		}
	}

	public void serialize(OutputStream stream,
	                      Object bean,
	                      Field field) throws IOException {
		this.engine.validateField(bean,
		                          field);
		try {
			Lifecycle.beforeSerialize(bean);
			Session session = new Session(this.engine.limits);
			this.engine.writeFieldRecord(stream,
			                             bean,
			                             field,
			                             new EncodeContext(this.engine,
			                                               session,
			                                               bean.getClass(),
			                                               CodecOptions.forClass(bean.getClass(),
			                                                                     this.engine.options),
			                                               1));
			Lifecycle.afterSerialize(bean);
		}
		catch (Throwable cause) {
			Lifecycle.serializeFailed(bean,
			                          cause);
			throw Lifecycle.propagate(cause);
		}
	}

	/**
	 * Requires EOF after one document; use deserialize for concatenated documents.
	 */
	public <T> T deserializeSingle(InputStream stream,
	                               Class<T> type) throws IOException {
		return this.deserializeDocument(stream,
		                                type,
		                                true);
	}

	public void deserialize(InputStream stream,
	                        Object bean,
	                        Field field) throws IOException {
		this.engine.validateField(bean,
		                          field);
		try {
			Lifecycle.beforeDeserialize(bean);
			Session session = new Session(this.engine.limits);
			this.engine.readSingleField(stream,
			                            bean,
			                            field,
			                            new DecodeContext(this.engine,
			                                              session,
			                                              bean.getClass(),
			                                              CodecOptions.forClass(bean.getClass(),
			                                                                    this.engine.options),
			                                              1));
			Lifecycle.afterDeserialize(bean);
		}
		catch (Throwable cause) {
			Lifecycle.deserializeFailed(bean,
			                            cause);
			throw Lifecycle.propagate(cause);
		}
	}

	public static final class Builder {
		private CodecOptions options = CodecOptions.defaults();
		private CodecLimits limits = CodecLimits.defaults();
		private final Map<String, Class<?>> types = new LinkedHashMap<>();
		private final List<CodecRegistry.Registration> codecs = new ArrayList<>();
		private final Map<String, Comparator<?>> comparators = new LinkedHashMap<>();
		private final Map<Class<?>, ContainerPolicyRegistry.Policy> policies = new LinkedHashMap<>();

		public Builder options(CodecOptions value) {
			this.options = Objects.requireNonNull(value);
			return this;
		}

		public Builder limits(CodecLimits value) {
			this.limits = Objects.requireNonNull(value);
			return this;
		}

		public Builder registerType(String id,
		                            Class<?> type) {
			unique(this.types,
			       id,
			       type);
			return this;
		}

		public Builder registerCodec(String id,
		                             int version,
		                             Object codec) {
			this.codecs.add(new CodecRegistry.Registration(id,
			                                               version,
			                                               codec));
			return this;
		}

		public Builder registerComparator(String id,
		                                  Comparator<?> comparator) {
			unique(this.comparators,
			       id,
			       comparator);
			return this;
		}

		public Builder registerContainerPolicy(Class<?> type,
		                                       ContainerPolicyRegistry.Policy policy) {
			unique(this.policies,
			       type,
			       policy);
			return this;
		}

		private static <K, V> void unique(Map<K, V> map,
		                                  K key,
		                                  V value) {
			Objects.requireNonNull(key);
			Objects.requireNonNull(value);
			if ((key instanceof String text && text.isEmpty()) || map.putIfAbsent(key,
			                                                                      value) != null) {
				throw new IllegalArgumentException("Duplicate/empty registration: " + key);
			}
		}

		public PojoCodecController build() {
			return new PojoCodecController(this);
		}
	}
}
