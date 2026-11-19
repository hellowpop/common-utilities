package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.annotation.*;
import com.inzent.apim.utilities.pojocodec.registry.ContainerPolicyRegistry;

import java.io.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AcceptanceTest {
	enum Key {
		A,
		B
	}

	static class EnumLists {
		List<String> a;
		List<Key> z;
	}

	static class EnumEmpty {
		EnumSet<Key> set = EnumSet.noneOf(Key.class);
		EnumMap<Key, String> map = new EnumMap<>(Key.class);
	}

	@PojoCodec(emptyPolicy = EmptyPolicy.INCLUDE)
	static class IncludedEnumEmpty
			extends EnumEmpty {
	}

	static class GenericBase<T> {
		T value;
		List<T> values;
		T[] array;
	}

	static class GenericChild
			extends GenericBase<String> {
	}

	static class ObjectBean {
		Object value;
	}

	static class Child {
		int n = 3;
	}

	static class Pair {
		Child a;
		Child b;
	}

	static class MapBean {
		Map<Instant, String> map;
	}

	static class CheckedBean {
		List<String> list = Collections.checkedList(new ArrayList<>(List.of("a")),
		                                            String.class);
	}

	static class DelayedBean
			implements Delayed {
		long millis = System.currentTimeMillis() + 86400000;

		public long getDelay(TimeUnit unit) {
			return unit.convert(millis - System.currentTimeMillis(),
			                    TimeUnit.MILLISECONDS);
		}

		public int compareTo(Delayed other) {
			return Long.compare(getDelay(TimeUnit.MILLISECONDS),
			                    other.getDelay(TimeUnit.MILLISECONDS));
		}
	}

	@Test
	void multipleGenericFieldsRegisterAllStaticElementTypes() throws Exception {
		EnumLists source = new EnumLists();
		source.a = List.of("x");
		source.z = List.of(Key.A);
		EnumLists result = PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                EnumLists.class,
		                                                source);
		Assertions.assertEquals(List.of(Key.A),
		                        result.z);
	}

	@Test
	void inheritedGenericsAndEmptyEnumContainersRestore() throws Exception {
		GenericChild source = new GenericChild();
		source.value = "x";
		source.values = List.of("a");
		source.array = new String[]{"b"};
		GenericChild result = PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                   GenericChild.class,
		                                                   source);
		Assertions.assertEquals("x",
		                        result.value);
		Assertions.assertArrayEquals(new String[]{"b"},
		                             result.array);
		IncludedEnumEmpty empty = PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                       IncludedEnumEmpty.class,
		                                                       new IncludedEnumEmpty());
		empty.set.add(Key.B);
		empty.map.put(Key.A,
		              "v");
		Assertions.assertEquals("v",
		                        empty.map.get(Key.A));
	}

	@Test
	void runtimePojoNeedsRegistrationAndSharedSiblingsAreIndependent() throws Exception {
		ObjectBean bean = new ObjectBean();
		bean.value = new Child();
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                           ObjectBean.class,
		                                                           bean));
		PojoCodecController registered = PojoCodecController.builder()
		                                                    .registerType("child",
		                                                                  Child.class)
		                                                    .build();
		Assertions.assertEquals(3,
		                        ((Child) PojoCodecControllerTest.copy(registered,
		                                                              ObjectBean.class,
		                                                              bean).value).n);
		Pair pair = new Pair();
		pair.a = new Child();
		pair.b = pair.a;
		Pair result = PojoCodecControllerTest.copy(new PojoCodecController(),
		                                           Pair.class,
		                                           pair);
		Assertions.assertNotSame(result.a,
		                         result.b);
		Assertions.assertEquals(3,
		                        result.b.n);
	}

	@Test
	void temporalKeyPrecisionLossMustNotSilentlyReplaceMapEntries() throws Exception {
		MapBean bean = new MapBean();
		bean.map = new HashMap<>();
		bean.map.put(Instant.ofEpochSecond(1,
		                                   1),
		             "a");
		bean.map.put(Instant.ofEpochSecond(1,
		                                   2),
		             "b");
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                           MapBean.class,
		                                                           bean));
	}

	@SuppressWarnings({"unchecked",
	                   "rawtypes"})
	@Test
	void checkedWrapperEnforcesDecodedGenericType() throws Exception {
		CheckedBean result = PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                  CheckedBean.class,
		                                                  new CheckedBean());
		Assertions.assertThrows(ClassCastException.class,
		                        () -> ((List) result.list).add(1));
	}

	@Test
	void blockingAndConcurrentContainersUseFullBinaryPipeline() throws Exception {
		List<Collection<?>> samples = List.of(new ArrayDeque<>(List.of("a")),
		                                      new LinkedBlockingQueue<>(List.of("a")),
		                                      new LinkedBlockingDeque<>(List.of("a")),
		                                      new ArrayBlockingQueue<>(3),
		                                      new PriorityBlockingQueue<>(List.of("a")),
		                                      new ConcurrentLinkedQueue<>(List.of("a")),
		                                      new ConcurrentLinkedDeque<>(List.of("a")),
		                                      new LinkedTransferQueue<>(List.of("a")),
		                                      new CopyOnWriteArrayList<>(List.of("a")),
		                                      new CopyOnWriteArraySet<>(List.of("a")),
		                                      new ConcurrentSkipListSet<>(List.of("a")),
		                                      new SynchronousQueue<>(),
		                                      Collections.synchronizedList(new ArrayList<>(List.of("a"))));
		PojoCodecController codec = new PojoCodecController();
		for (Collection<?> sample : samples) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			codec.serialize(out,
			                sample);
			Collection<?> restored = codec.deserialize(new ByteArrayInputStream(out.toByteArray()),
			                                           Collection.class);
			Assertions.assertEquals(sample.getClass(),
			                        restored.getClass());
			Assertions.assertEquals(new ArrayList<>(sample),
			                        new ArrayList<>(restored));
		}
		DelayQueue<DelayedBean> queue = new DelayQueue<>();
		queue.add(new DelayedBean());
		PojoCodecController delayedCodec = PojoCodecController.builder()
		                                                      .registerType("delayed",
		                                                                    DelayedBean.class)
		                                                      .build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		delayedCodec.serialize(out,
		                       queue);
		DelayQueue<?> restored = delayedCodec.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                                                  DelayQueue.class);
		Assertions.assertEquals(1,
		                        restored.size());
		Assertions.assertNull(restored.poll());
	}

	@Test
	void independentCallsOnOneControllerAreThreadSafe() throws Exception {
		PojoCodecController codec = new PojoCodecController();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<Integer>> jobs = new ArrayList<>();
			for (int i = 0; i < 50; i++) {
				int n = i;
				jobs.add(pool.submit(() -> {
					Child child = new Child();
					child.n = n;
					return PojoCodecControllerTest.copy(codec,
					                                    Child.class,
					                                    child).n;
				}));
			}
			for (int i = 0; i < jobs.size(); i++) {
				Assertions.assertEquals(i,
				                        jobs.get(i)
				                            .get());
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void readerMustNotProjectSubclassDocumentIntoRequestedBase() throws Exception {
		PojoCodecController codec = PojoCodecController.builder()
		                                               .registerType("sub",
		                                                             PojoCodecControllerTest.Bean.class)
		                                               .build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.serialize(out,
		                new PojoCodecControllerTest.Bean());
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> codec.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                                                PojoCodecControllerTest.Base.class));
	}
}
