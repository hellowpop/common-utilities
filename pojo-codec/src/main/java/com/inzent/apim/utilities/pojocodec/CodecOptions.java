package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.annotation.*;

import java.util.Objects;

public record CodecOptions(NullPolicy nullPolicy,
                           EmptyPolicy emptyPolicy,
                           ZonePolicy zonePolicy) {
	public CodecOptions {
		Objects.requireNonNull(nullPolicy);
		Objects.requireNonNull(emptyPolicy);
		Objects.requireNonNull(zonePolicy);
		if (nullPolicy == NullPolicy.INHERIT || emptyPolicy == EmptyPolicy.INHERIT || zonePolicy == ZonePolicy.INHERIT) {
			throw new IllegalArgumentException("Global/effective options must be resolved");
		}
	}

	public static CodecOptions defaults() {
		return new CodecOptions(NullPolicy.OMIT,
		                        EmptyPolicy.OMIT,
		                        ZonePolicy.OMIT);
	}

	public CodecOptions overlay(PojoCodec annotation) {
		if (annotation == null) {
			return this;
		}
		return new CodecOptions(annotation.nullPolicy() == NullPolicy.INHERIT
		                        ? nullPolicy
		                        : annotation.nullPolicy(),
		                        annotation.emptyPolicy() == EmptyPolicy.INHERIT
		                        ? emptyPolicy
		                        : annotation.emptyPolicy(),
		                        annotation.zonePolicy() == ZonePolicy.INHERIT
		                        ? zonePolicy
		                        : annotation.zonePolicy());
	}

	public static CodecOptions forClass(Class<?> type,
	                                    CodecOptions fallback) {
		if (type == null || type == Object.class) {
			return fallback;
		}
		return forClass(type.getSuperclass(),
		                fallback).overlay(type.getDeclaredAnnotation(PojoCodec.class));
	}
}
