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
		engine = new Engine(builder.options,
		                    builder.limits,
		                    new TypeRegistry(builder.types),
		                    new CodecRegistry(builder.codecs),
		                    new ComparatorRegistry(builder.comparators),
		                    new ContainerPolicyRegistry(builder.policies));
		compiled = new CompiledCodec(engine);
		try {
			for (Class<?> type : builder.types.values()) {
				engine.prepare(type);
			}
			for (ContainerPolicyRegistry.Policy policy : builder.policies.values()) {
				if (policy.enumType() != null) {
					engine.prepare(policy.enumType());
				}
				if (policy.checkedKeyType() != null) {
					engine.prepare(policy.checkedKeyType());
				}
				if (policy.checkedValueType() != null) {
					engine.prepare(policy.checkedValueType());
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
		serializeRoot(stream,
		              bean.getClass(),
		              bean);
	}

	public <T> void serialize(OutputStream stream,
	                          Class<T> type,
	                          T bean) throws IOException {
		serializeRoot(stream,
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
			serializeDocument(stream,
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
		if (compiled.write(stream,
		                   type,
		                   bean)) {
			return;
		}
		engine.prepare(type);
		Session session = new Session(engine.limits);
		EncodeContext context = new EncodeContext(engine,
		                                          session,
		                                          type,
		                                          engine.options,
		                                          1);
		try (BudgetedBuffer root = new BudgetedBuffer(session,
		                                              Math.max(0,
		                                                       engine.limits.documentBytes() - 12))) {
			engine.writeValue(root,
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
		return deserializeDocument(stream,
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
		if (compiled.supports(type)) {
			T result = type.cast(compiled.read(stream,
			                                   type));
			if (strict) {
				FrameIO.exhausted(stream);
			}
			return result;
		}
		Session session = new Session(engine.limits);
		try {
			engine.prepare(type);
			DataInputStream in = new DataInputStream(stream);
			if (in.readInt() != 0x504a4344 || in.readUnsignedShort() != 1 || in.readUnsignedShort() != 0) {
				throw session.error("Invalid document header/version/flags");
			}
			BoundedInputStream root = new BoundedInputStream(in,
			                                                 FrameIO.length(in,
			                                                                Math.max(0,
			                                                                         engine.limits.documentBytes() - 12)));
			Object value = engine.readValue(root,
			                                type,
			                                new DecodeContext(engine,
			                                                  session,
			                                                  type,
			                                                  engine.options,
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
		engine.validateField(bean,
		                     field);
		try {
			Lifecycle.beforeSerialize(bean);
			Session session = new Session(engine.limits);
			engine.writeFieldRecord(stream,
			                        bean,
			                        field,
			                        new EncodeContext(engine,
			                                          session,
			                                          bean.getClass(),
			                                          CodecOptions.forClass(bean.getClass(),
			                                                                engine.options),
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
		return deserializeDocument(stream,
		                           type,
		                           true);
	}

	public void deserialize(InputStream stream,
	                        Object bean,
	                        Field field) throws IOException {
		engine.validateField(bean,
		                     field);
		try {
			Lifecycle.beforeDeserialize(bean);
			Session session = new Session(engine.limits);
			engine.readSingleField(stream,
			                       bean,
			                       field,
			                       new DecodeContext(engine,
			                                         session,
			                                         bean.getClass(),
			                                         CodecOptions.forClass(bean.getClass(),
			                                                               engine.options),
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
			options = Objects.requireNonNull(value);
			return this;
		}

		public Builder limits(CodecLimits value) {
			limits = Objects.requireNonNull(value);
			return this;
		}

		public Builder registerType(String id,
		                            Class<?> type) {
			unique(types,
			       id,
			       type);
			return this;
		}

		public Builder registerCodec(String id,
		                             int version,
		                             Object codec) {
			codecs.add(new CodecRegistry.Registration(id,
			                                          version,
			                                          codec));
			return this;
		}

		public Builder registerComparator(String id,
		                                  Comparator<?> comparator) {
			unique(comparators,
			       id,
			       comparator);
			return this;
		}

		public Builder registerContainerPolicy(Class<?> type,
		                                       ContainerPolicyRegistry.Policy policy) {
			unique(policies,
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
