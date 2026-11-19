package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.registry.ComparatorRegistry;
import com.inzent.apim.utilities.pojocodec.registry.TypeRegistry;

import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RegistryTest {
	@Test
	void resolvesOnlyAllowedTypes() throws Exception {
		TypeRegistry registry = new TypeRegistry(Map.of("text",
		                                                String.class));
		Assertions.assertEquals(String.class,
		                        registry.resolve("text"));
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> registry.resolve("java.lang.Runtime"));
	}

	@Test
	void comparatorIdentityMustBeRegistered() throws Exception {
		Comparator<String> comparator = Comparator.comparingInt(String::length);
		ComparatorRegistry registry = new ComparatorRegistry(Map.of("length",
		                                                            comparator));
		Assertions.assertSame(comparator,
		                      registry.resolve("length"));
		Assertions.assertEquals("reverse-natural",
		                        registry.id(Comparator.reverseOrder()));
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> registry.id(String.CASE_INSENSITIVE_ORDER));
	}
}
