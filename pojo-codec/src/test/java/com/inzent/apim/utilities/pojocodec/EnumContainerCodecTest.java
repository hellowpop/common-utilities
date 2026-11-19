package com.inzent.apim.utilities.pojocodec;

import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class EnumContainerCodecTest {
	enum Key {
		A,
		B
	}

	@Test
	void enumContainersAreBuiltInAndRawEmptyNeedsType() throws Exception {
		Assertions.assertEquals(EnumSet.of(Key.B),
		                        BasicContainerCodecTest.copy(EnumSet.of(Key.B)));
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> BasicContainerCodecTest.copy(EnumSet.noneOf(Key.class)));
		EnumMap<Key, Object> map = new EnumMap<>(Key.class);
		map.put(Key.A,
		        "a");
		Assertions.assertEquals(map,
		                        BasicContainerCodecTest.copy(map));
	}
}
