package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.registry.ContainerPolicyRegistry;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ContainerPolicyRegressionTest {
	enum Key {
		A,
		B
	}

	private PojoCodecController controller(Class<?> container,
	                                       ContainerPolicyRegistry.Policy policy) {
		return PojoCodecController.builder()
		                          .registerContainerPolicy(container,
		                                                   policy)
		                          .build();
	}

	private Object copySeparate(Object source,
	                            Class<?> target,
	                            ContainerPolicyRegistry.Policy policy) throws Exception {
		PojoCodecController writer = this.controller(source.getClass(),
		                                             policy), reader = this.controller(source.getClass(),
		                                                                               policy);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writer.serialize(out,
		                 source);
		return reader.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                          target);
	}

	@SuppressWarnings("unchecked")
	@Test
	void freshEnumPolicyReaderCanResolveRawEmptyEnumContainers() throws Exception {
		EnumSet<Key> set = (EnumSet<Key>) this.copySeparate(EnumSet.noneOf(Key.class),
		                                                    EnumSet.class,
		                                                    ContainerPolicyRegistry.Policy.enumType(Key.class));
		set.add(Key.B);
		Assertions.assertEquals(EnumSet.of(Key.B),
		                        set);
		EnumMap<Key, Object> map = (EnumMap<Key, Object>) this.copySeparate(new EnumMap<Key, Object>(Key.class),
		                                                                    EnumMap.class,
		                                                                    ContainerPolicyRegistry.Policy.enumType(Key.class));
		map.put(Key.A,
		        "x");
		Assertions.assertEquals("x",
		                        map.get(Key.A));
	}

	@SuppressWarnings({"rawtypes",
	                   "unchecked"})
	@Test
	void freshCheckedPolicyReaderRegistersElementAndMapValueTypes() throws Exception {
		List<?> result = (List<?>) this.copySeparate(Collections.checkedList(new ArrayList<>(),
		                                                                     Integer.class),
		                                             List.class,
		                                             ContainerPolicyRegistry.Policy.checked(Integer.class));
		Assertions.assertThrows(ClassCastException.class,
		                        () -> ((List) result).add("wrong"));
		Map<?, ?> map = (Map<?, ?>) this.copySeparate(Collections.checkedMap(new HashMap<>(),
		                                                                     Integer.class,
		                                                                     Long.class),
		                                              Map.class,
		                                              ContainerPolicyRegistry.Policy.checkedMap(Integer.class,
		                                                                                        Long.class));
		((Map) map).put(1,
		                2L);
		Assertions.assertEquals(2L,
		                        map.get(1));
		Assertions.assertThrows(ClassCastException.class,
		                        () -> ((Map) map).put(1,
		                                              "wrong"));
	}

	@Test
	void checkedLinkedQueueRetainsNullAndFifoOrder() throws Exception {
		Queue<String> source = Collections.checkedQueue(new LinkedList<>(),
		                                                String.class);
		source.add(null);
		source.add("next");
		Queue<?> result = (Queue<?>) this.copySeparate(source,
		                                               Queue.class,
		                                               ContainerPolicyRegistry.Policy.checked(String.class));
		Assertions.assertEquals(2,
		                        result.size());
		Assertions.assertNull(result.remove());
		Assertions.assertEquals("next",
		                        result.remove());
	}

	@SuppressWarnings("unchecked")
	@Test
	void explicitObjectCheckTypeIsKnownRatherThanMissing() throws Exception {
		List<Object> source = Collections.checkedList(new ArrayList<>(),
		                                              Object.class);
		source.add("x");
		source.add(1);
		List<Object> result = (List<Object>) this.copySeparate(source,
		                                                       List.class,
		                                                       ContainerPolicyRegistry.Policy.checked(Object.class));
		result.add(false);
		Assertions.assertEquals(List.of("x",
		                                1,
		                                false),
		                        result);
	}
}
