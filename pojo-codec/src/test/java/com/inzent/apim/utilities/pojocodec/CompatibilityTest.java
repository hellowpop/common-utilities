package com.inzent.apim.utilities.pojocodec;

import java.io.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CompatibilityTest {
	static class Old {
		int amount = 5;
		String deleted = "old";
	}

	static class New {
		long amount;
		String added = "init";
	}

	@Test
	void stableTypeIdAllowsFieldRemovalAdditionAndWidening() throws Exception {
		PojoCodecController old = PojoCodecController.builder()
		                                             .registerType("record",
		                                                           Old.class)
		                                             .build();
		PojoCodecController newer = PojoCodecController.builder()
		                                               .registerType("record",
		                                                             New.class)
		                                               .build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		old.serialize(out,
		              new Old());
		New result = newer.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                               New.class);
		Assertions.assertEquals(5L,
		                        result.amount);
		Assertions.assertEquals("init",
		                        result.added);
	}
}
