package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.metadata.GenericTypeResolver;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GenericTypeResolverTest {
	static class Base<T> {
		List<T[]> items;
		T value;
	}

	static class Middle<X>
			extends Base<X> {
	}

	static class Strings
			extends Middle<String> {
	}

	@Test
	void inheritedTypeVariablesResolveInsideArraysAndCollections() throws Exception {
		Assertions.assertEquals(String.class,
		                        GenericTypeResolver.resolve(Base.class.getDeclaredField("value")
		                                                              .getGenericType(),
		                                                    Strings.class));
		ParameterizedType list = (ParameterizedType) GenericTypeResolver.resolve(Base.class.getDeclaredField("items")
		                                                                                   .getGenericType(),
		                                                                         Strings.class);
		Assertions.assertEquals(String[].class,
		                        list.getActualTypeArguments()[0]);
	}
}
