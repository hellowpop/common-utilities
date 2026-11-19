package com.inzent.apim.utilities.pojocodec;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConcurrentContainerCodecTest {
	@Test
	void concurrentKindsAreNotConvertedToMutableOrdinaryCollections() throws Exception {
		List<Object> samples = List.of(new CopyOnWriteArrayList<>(List.of("a")),
		                               new CopyOnWriteArraySet<>(List.of("a")),
		                               new ConcurrentHashMap<>(Map.of("a",
		                                                              1)),
		                               new ConcurrentSkipListMap<>(Map.of("a",
		                                                                  1)),
		                               new ConcurrentSkipListSet<>(List.of("a")),
		                               new ConcurrentLinkedQueue<>(List.of("a")),
		                               new ConcurrentLinkedDeque<>(List.of("a")),
		                               new LinkedTransferQueue<>(List.of("a")));
		for (Object sample : samples) {
			Assertions.assertEquals(sample.getClass(),
			                        BasicContainerCodecTest.copy(sample)
			                                               .getClass());
		}
	}
}
