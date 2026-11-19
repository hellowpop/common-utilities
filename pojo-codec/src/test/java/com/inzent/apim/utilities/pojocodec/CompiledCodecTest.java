package com.inzent.apim.utilities.pojocodec;

import com.sun.management.ThreadMXBean;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CompiledCodecTest {
	static class Flat {
		int count = 42;
		long id = 123456789L;
		boolean active = true;
		double amount = 12345.67;
		String name = "홍길동";
		String description = "bounded frames";
	}

	static class Nested {
		List<Flat> items = new ArrayList<>(List.of(new Flat(),
		                                           new Flat()));
		int[] values = {1,
		                -2,
		                3};
	}

	static class Before {
		int veryLongDeletedFieldName = 1;
	}

	static class After {
	}

	static class PrimitiveOnly {
		int value = 1;
	}

	@Test
	void inlinePrimitiveFieldsStillConsumeAValueDepthLevel() throws Exception {
		byte[] bytes = this.encode(new PojoCodecController(),
		                           new PrimitiveOnly());
		CodecLimits defaults = CodecLimits.defaults();
		CodecLimits limits = new CodecLimits(defaults.documentBytes(),
		                                     defaults.frameBytes(),
		                                     defaults.stringBytes(),
		                                     defaults.identifierBytes(),
		                                     defaults.containerItems(),
		                                     defaults.fields(),
		                                     1,
		                                     defaults.bufferBytes(),
		                                     defaults.totalItems());
		PojoCodecController codec = PojoCodecController.builder()
		                                               .limits(limits)
		                                               .build();
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> codec.deserialize(new ByteArrayInputStream(bytes),
		                                                PrimitiveOnly.class));
	}

	@Test
	void registeredIdentifiersMustNotSilentlyReplaceMalformedUtf16() {
		PojoCodecController codec = PojoCodecController.builder()
		                                               .registerType("bad\ud800",
		                                                             PrimitiveOnly.class)
		                                               .build();
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> codec.serialize(new ByteArrayOutputStream(),
		                                              new PrimitiveOnly()));
	}

	static class ArraysBean {
		boolean[] a = {true,
		               false};
		byte[] b = {1,
		            -2};
		short[] c = {1,
		             -2};
		int[] d = {Integer.MIN_VALUE,
		           Integer.MAX_VALUE};
		long[] e = {Long.MIN_VALUE,
		            Long.MAX_VALUE};
		float[] f = {-0.0f,
		             Float.NaN};
		double[] g = {Double.POSITIVE_INFINITY,
		              -0.0};
		char[] h = {'한',
		            '\uffff'};
		String text = "😀\u0000한";
	}

	private byte[] referenceBytes(Class<?> type,
	                              Object value) throws Exception {
		com.inzent.apim.utilities.pojocodec.internal.Engine engine = new com.inzent.apim.utilities.pojocodec.internal.Engine(CodecOptions.defaults(),
		                                                                                                                     CodecLimits.defaults(),
		                                                                                                                     new com.inzent.apim.utilities.pojocodec.registry.TypeRegistry(Map.of()),
		                                                                                                                     new com.inzent.apim.utilities.pojocodec.registry.CodecRegistry(List.of()),
		                                                                                                                     new com.inzent.apim.utilities.pojocodec.registry.ComparatorRegistry(Map.of()),
		                                                                                                                     new com.inzent.apim.utilities.pojocodec.registry.ContainerPolicyRegistry(Map.of()));
		engine.prepare(type);
		com.inzent.apim.utilities.pojocodec.internal.Session session = new com.inzent.apim.utilities.pojocodec.internal.Session(CodecLimits.defaults());
		ByteArrayOutputStream frame = new ByteArrayOutputStream();
		engine.writeValue(frame,
		                  value,
		                  type,
		                  new EncodeContext(engine,
		                                    session,
		                                    type,
		                                    CodecOptions.defaults(),
		                                    1),
		                  true);
		ByteArrayOutputStream document = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(document);
		out.writeInt(0x504a4344);
		out.writeInt(0x00010000);
		out.writeInt(frame.size());
		frame.writeTo(out);
		return document.toByteArray();
	}

	@Test
	void optimizedOutputMatchesExistingV1ForEveryPrimitiveArray() throws Exception {
		PojoCodecController codec = new PojoCodecController();
		ArraysBean bean = new ArraysBean();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.serialize(out,
		                bean);
		Assertions.assertArrayEquals(this.referenceBytes(ArraysBean.class,
		                                                 bean),
		                             out.toByteArray());
		ArraysBean result = codec.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                                      ArraysBean.class);
		Assertions.assertArrayEquals(bean.a,
		                             result.a);
		Assertions.assertArrayEquals(bean.b,
		                             result.b);
		Assertions.assertArrayEquals(bean.c,
		                             result.c);
		Assertions.assertArrayEquals(bean.d,
		                             result.d);
		Assertions.assertArrayEquals(bean.e,
		                             result.e);
		Assertions.assertArrayEquals(bean.f,
		                             result.f);
		Assertions.assertArrayEquals(bean.g,
		                             result.g);
		Assertions.assertArrayEquals(bean.h,
		                             result.h);
		Assertions.assertEquals(bean.text,
		                        result.text);
		Assertions.assertArrayEquals(this.referenceBytes(Nested.class,
		                                                 new Nested()),
		                             this.encode(codec,
		                                         new Nested()));
	}

	private byte[] encode(PojoCodecController codec,
	                      Object bean) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.serialize(out,
		                bean);
		return out.toByteArray();
	}

	@Test
	void idleThreadDoesNotKeepDiscardedControllersAlive() throws Exception {
		ExecutorService thread = Executors.newSingleThreadExecutor();
		try {
			WeakReference<Object> reference = thread.submit(() -> {
				                                        Object plugin = new CustomCodecTest.UpperFieldCodec();
				                                        PojoCodecController controller = PojoCodecController.builder()
				                                                                                            .registerCodec("retention-probe",
				                                                                                                           1,
				                                                                                                           plugin)
				                                                                                            .build();
				                                        controller.serialize(new ByteArrayOutputStream(),
				                                                             new Flat());
				                                        return new WeakReference<>(plugin);
			                                        })
			                                        .get();
			for (int i = 0; i < 30 && reference.get() != null; i++) {
				System.gc();
				Thread.sleep(20);
			}
			Assertions.assertNull(reference.get(),
			                      "Pooled scratch retains discarded controller");
		}
		finally {
			thread.shutdownNow();
		}
	}

	@Test
	void identifierAndStringLimitsRemainIndependentDuringSchemaEvolution() throws Exception {
		CodecLimits limits = new CodecLimits(4096,
		                                     2048,
		                                     1,
		                                     1024,
		                                     100,
		                                     100,
		                                     20,
		                                     16384,
		                                     1000);
		PojoCodecController writer = PojoCodecController.builder()
		                                                .limits(limits)
		                                                .registerType("r",
		                                                              Before.class)
		                                                .build();
		PojoCodecController reader = PojoCodecController.builder()
		                                                .limits(limits)
		                                                .registerType("r",
		                                                              After.class)
		                                                .build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writer.serialize(out,
		                 new Before());
		Assertions.assertNotNull(reader.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                                            After.class));
	}

	@Test
	void nullRootDoesNotRequireItsUnwrittenTypeIdentifierToFit() throws Exception {
		CodecLimits limits = new CodecLimits(4096,
		                                     2048,
		                                     1024,
		                                     1,
		                                     100,
		                                     100,
		                                     20,
		                                     16384,
		                                     1000);
		PojoCodecController codec = PojoCodecController.builder()
		                                               .limits(limits)
		                                               .build();
		Assertions.assertNull(PojoCodecControllerTest.copy(codec,
		                                                   Flat.class,
		                                                   null));
	}

	@Test
	void nullRootFitsSmallBufferBudgetsEvenWhenUnusedPlansAreLarger() throws Exception {
		CodecLimits defaults = CodecLimits.defaults();
		CodecLimits limits = new CodecLimits(defaults.documentBytes(),
		                                     defaults.frameBytes(),
		                                     defaults.stringBytes(),
		                                     defaults.identifierBytes(),
		                                     defaults.containerItems(),
		                                     defaults.fields(),
		                                     defaults.depth(),
		                                     64,
		                                     defaults.totalItems());
		PojoCodecController codec = PojoCodecController.builder()
		                                               .limits(limits)
		                                               .build();
		Assertions.assertNull(PojoCodecControllerTest.copy(codec,
		                                                   Flat.class,
		                                                   null));
		Assertions.assertNull(PojoCodecControllerTest.copy(codec,
		                                                   Nested.class,
		                                                   null));
	}

	@Test
	void hotPojoPathHasBoundedAllocation() throws Exception {
		Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean);
		ThreadMXBean meter = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		Assumptions.assumeTrue(meter.isThreadAllocatedMemorySupported());
		meter.setThreadAllocatedMemoryEnabled(true);
		PojoCodecController codec = new PojoCodecController();
		Flat bean = new Flat();
		ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
		codec.serialize(out,
		                bean);
		byte[] bytes = out.toByteArray();
		for (int i = 0; i < 10000; i++) {
			out.reset();
			codec.serialize(out,
			                bean);
			codec.deserialize(new ByteArrayInputStream(bytes),
			                  Flat.class);
		}
		long thread = Thread.currentThread()
		                    .getId(), start = meter.getThreadAllocatedBytes(thread);
		for (int i = 0; i < 1000; i++) {
			out.reset();
			codec.serialize(out,
			                bean);
		}
		long serializeBytes = (meter.getThreadAllocatedBytes(thread) - start) / 1000;
		start = meter.getThreadAllocatedBytes(thread);
		for (int i = 0; i < 1000; i++) {
			codec.deserialize(new ByteArrayInputStream(bytes),
			                  Flat.class);
		}
		long deserializeBytes = (meter.getThreadAllocatedBytes(thread) - start) / 1000;
		Assertions.assertTrue(serializeBytes < 512,
		                      "serialize bytes/op=" + serializeBytes);
		Assertions.assertTrue(deserializeBytes < 1024,
		                      "deserialize bytes/op=" + deserializeBytes);
	}

	@Test
	void nestedListsArraysAndStrictUtf8StillRoundTrip() throws Exception {
		Nested source = new Nested();
		Nested result = PojoCodecControllerTest.copy(new PojoCodecController(),
		                                             Nested.class,
		                                             source);
		Assertions.assertArrayEquals(new int[]{1,
		                                       -2,
		                                       3},
		                             result.values);
		Assertions.assertEquals("홍길동",
		                        result.items.get(1).name);
		source.items.get(0).name = "bad\ud800";
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                           Nested.class,
		                                                           source));
	}

	@Test
	void callbackReentrancyDoesNotOverwritePendingBytes() throws Exception {
		PojoCodecController codec = new PojoCodecController();
		ByteArrayOutputStream received = new ByteArrayOutputStream();
		OutputStream callback = new OutputStream() {
			@Override
			public void write(int value) {
				received.write(value);
			}

			@Override
			public void write(byte[] bytes,
			                  int offset,
			                  int size) throws IOException {
				Flat inner = new Flat();
				inner.count = 99;
				codec.serialize(new ByteArrayOutputStream(),
				                inner);
				received.write(bytes,
				               offset,
				               size);
			}
		};
		codec.serialize(callback,
		                new Flat());
		Assertions.assertEquals(42,
		                        codec.deserialize(new ByteArrayInputStream(received.toByteArray()),
		                                          Flat.class).count);
	}
}
