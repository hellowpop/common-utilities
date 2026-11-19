package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.metadata.MetadataResolver;
import com.inzent.apim.utilities.pojocodec.metadata.ClassMetadata;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MetadataResolverTest {
	static class Parent {
		private int z;
		transient int ignored;
		static int shared;
	}

	static class Child
			extends Parent {
		String a;
	}

	static class Conflict
			extends Parent {
		int z;
	}

	static class FinalField {
		final int value = 1;
	}

	@Test
	void inheritedFieldsAreSortedAndNonPersistentFieldsExcluded() throws Exception {
		ClassMetadata metadata = MetadataResolver.resolve(Child.class);
		Assertions.assertEquals(java.util.List.of("a",
		                                          "z"),
		                        metadata.fields()
		                                .stream()
		                                .map(field -> field.field()
		                                                   .getName())
		                                .toList());
	}

	@Test
	void ambiguousAndFinalFieldsFailExplicitly() {
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> MetadataResolver.resolve(Conflict.class));
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> MetadataResolver.resolve(FinalField.class));
	}
}
