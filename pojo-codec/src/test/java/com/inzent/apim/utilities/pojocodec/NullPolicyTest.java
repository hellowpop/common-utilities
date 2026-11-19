package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.annotation.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NullPolicyTest {
	static class Bean {
		String value = "seed";
		List<String> items = new ArrayList<>(List.of("seed"));
	}

	@PojoCodec(nullPolicy = NullPolicy.INCLUDE,
	           emptyPolicy = EmptyPolicy.INCLUDE)
	static class Included
			extends Bean {
	}

	@Test
	void omittedNullKeepsInitializerAndIncludedNullOverridesIt() throws Exception {
		PojoCodecController codec = new PojoCodecController();
		Bean bean = new Bean();
		bean.value = null;
		bean.items.clear();
		Bean result = PojoCodecControllerTest.copy(codec,
		                                           Bean.class,
		                                           bean);
		Assertions.assertEquals("seed",
		                        result.value);
		Assertions.assertEquals(List.of("seed"),
		                        result.items);
		Included included = new Included();
		included.value = null;
		included.items.clear();
		Included decoded = PojoCodecControllerTest.copy(codec,
		                                                Included.class,
		                                                included);
		Assertions.assertNull(decoded.value);
		Assertions.assertTrue(decoded.items.isEmpty());
	}

	@Test
	void explicitNullIsReadByOmitReaderAndFieldAbsentPreservesCurrentValue() throws Exception {
		PojoCodecController writer = PojoCodecController.builder()
		                                                .options(new CodecOptions(NullPolicy.INCLUDE,
		                                                                          EmptyPolicy.OMIT,
		                                                                          ZonePolicy.OMIT))
		                                                .build();
		PojoCodecController reader = new PojoCodecController();
		Bean bean = new Bean();
		bean.value = null;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writer.serialize(out,
		                 Bean.class,
		                 bean);
		Assertions.assertNull(reader.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                                         Bean.class).value);
		out.reset();
		reader.serialize(out,
		                 bean,
		                 Bean.class.getDeclaredField("value"));
		Bean target = new Bean();
		reader.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                   target,
		                   Bean.class.getDeclaredField("value"));
		Assertions.assertEquals("seed",
		                        target.value);
		out.reset();
		writer.serialize(out,
		                 bean,
		                 Bean.class.getDeclaredField("value"));
		reader.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                   target,
		                   Bean.class.getDeclaredField("value"));
		Assertions.assertNull(target.value);
	}
}
