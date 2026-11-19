package com.inzent.apim.utilities.pojocodec.metadata;

import com.inzent.apim.utilities.pojocodec.PojoCodecException;

import java.lang.reflect.*;
import java.util.*;

public final class MetadataResolver {
	private MetadataResolver() {
	}

	private record Result(ClassMetadata value,
	                      String error) {
	}

	private static final ClassValue<Result> CACHE = new ClassValue<>() {
		@Override
		protected Result computeValue(Class<?> type) {
			try {
				if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
					return new Result(null,
					                  "Concrete POJO type required");
				}
				Constructor<?> constructor = type.getDeclaredConstructor();
				if (!constructor.trySetAccessible()) {
					return new Result(null,
					                  "Constructor inaccessible: " + type.getName());
				}
				TreeMap<String, FieldMetadata> fields = new TreeMap<>();
				for (Class<?> current = type; current != Object.class && current != null; current = current.getSuperclass()) {
					for (Field field : current.getDeclaredFields()) {
						int modifiers = field.getModifiers();
						if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
							continue;
						}
						if (Modifier.isFinal(modifiers)) {
							return new Result(null,
							                  "Final field requires custom Codec: " + field);
						}
						if (!field.trySetAccessible()) {
							return new Result(null,
							                  "Field inaccessible: " + field);
						}
						if (fields.put(field.getName(),
						               new FieldMetadata(field)) != null) {
							return new Result(null,
							                  "Duplicate field name: " + field.getName());
						}
					}
				}
				return new Result(new ClassMetadata(type,
				                                    constructor,
				                                    new ArrayList<>(fields.values())),
				                  null);
			}
			catch (ReflectiveOperationException | RuntimeException exception) {
				return new Result(null,
				                  "POJO metadata: " + type.getName() + ": " + exception);
			}
		}
	};

	public static ClassMetadata resolve(Class<?> type) throws PojoCodecException {
		Result result = CACHE.get(type);
		if (result.error() != null) {
			throw new PojoCodecException(result.error());
		}
		return result.value();
	}
}
