package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.annotation.PojoCodec;
import com.inzent.apim.utilities.pojocodec.codec.AbstractTypeCodec;
import com.inzent.apim.utilities.pojocodec.codec.DefaultTypeCodec;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PojoInjectableTest {
	private static final List<String> EVENTS = new ArrayList<>();
	private static Throwable failure;
	private static RuntimeException beforeWriteFailure, afterWriteFailure, beforeReadFailure, afterReadFailure, failureHookFailure;

	public static class Bean
			implements PojoInjectable {
		String value = "seed";

		public Bean() {
			EVENTS.add("new");
		}

		@Override
		public void beforeSerialize() {
			EVENTS.add("beforeS:" + this.value);
			if (beforeWriteFailure != null) {
				throw beforeWriteFailure;
			}
			this.value = "prepared";
		}

		@Override
		public void afterSerialize() {
			EVENTS.add("afterS");
			if (afterWriteFailure != null) {
				throw afterWriteFailure;
			}
			this.value = "after-write";
		}

		@Override
		public void onSerializeFail(Throwable cause) {
			EVENTS.add("failS");
			failure = cause;
			if (failureHookFailure != null) {
				throw failureHookFailure;
			}
		}

		@Override
		public void beforeDeserialize() {
			EVENTS.add("beforeD:" + this.value);
			if (beforeReadFailure != null) {
				throw beforeReadFailure;
			}
			this.value = "before-read";
		}

		@Override
		public void afterDeserialize() {
			EVENTS.add("afterD:" + this.value);
			if (afterReadFailure != null) {
				throw afterReadFailure;
			}
			this.value += ":after-read";
		}

		@Override
		public void onDeserializeFail(Throwable cause) {
			EVENTS.add("failD");
			failure = cause;
			if (failureHookFailure != null) {
				throw failureHookFailure;
			}
		}
	}

	static class Parent
			implements PojoInjectable {
		Bean child = new Bean();

		@Override
		public void beforeSerialize() {
			EVENTS.add("parent.beforeS");
		}

		@Override
		public void afterSerialize() {
			EVENTS.add("parent.afterS");
		}

		@Override
		public void beforeDeserialize() {
			EVENTS.add("parent.beforeD");
		}

		@Override
		public void afterDeserialize() {
			EVENTS.add("parent.afterD");
		}

		@Override
		public void onSerializeFail(Throwable cause) {
			EVENTS.add("parent.failS");
		}

		@Override
		public void onDeserializeFail(Throwable cause) {
			EVENTS.add("parent.failD");
		}
	}

	static class PlainParent {
		List<Bean> items = new ArrayList<>();
	}

	enum InjectableEnum
			implements PojoInjectable {
		VALUE;

		@Override
		public void beforeSerialize() {
			EVENTS.add("enum.beforeS");
		}

		@Override
		public void afterSerialize() {
			EVENTS.add("enum.afterS");
		}

		@Override
		public void beforeDeserialize() {
			EVENTS.add("enum.beforeD");
			if (beforeReadFailure != null) {
				throw beforeReadFailure;
			}
		}

		@Override
		public void afterDeserialize() {
			EVENTS.add("enum.afterD");
		}

		@Override
		public void onDeserializeFail(Throwable cause) {
			EVENTS.add("enum.failD");
			failure = cause;
		}
	}

	static class EnumOwner {
		InjectableEnum value = InjectableEnum.VALUE;
	}

	static class Base {
		String value = "base";
	}

	static class DynamicParent {
		Base child;
	}

	static class InjectableSubclass
			extends Base
			implements PojoInjectable {
		@Override
		public void beforeSerialize() {
			EVENTS.add("project.before");
			this.value = "prepared-base";
		}

		@Override
		public void afterSerialize() {
			EVENTS.add("project.after");
		}

		@Override
		public void beforeDeserialize() {
			EVENTS.add("project.beforeD");
		}

		@Override
		public void afterDeserialize() {
			EVENTS.add("project.afterD");
		}
	}

	@PojoCodec(typeCodec = InjectableCodec.class)
	public static class CustomBean
			extends Bean {
	}

	public static class InjectableCodec
			extends AbstractTypeCodec<CustomBean> {
		@Override
		public void writeBody(OutputStream out,
		                      CustomBean bean,
		                      EncodeContext context) throws IOException {
			context.writeValue(out,
			                   bean.value,
			                   String.class);
		}

		@Override
		public void readInto(InputStream in,
		                     CustomBean bean,
		                     DecodeContext context) throws IOException {
			EVENTS.add("codec.read:" + bean.value);
			bean.value = (String) context.readValue(in,
			                                        String.class);
		}
	}

	@PojoCodec(typeCodec = LegacyReader.class)
	public static class LegacyBean
			extends Bean {
	}

	public static class LegacyReader
			extends DefaultTypeCodec<LegacyBean> {
		@Override
		public LegacyBean readBody(InputStream in,
		                           Class<LegacyBean> type,
		                           DecodeContext context) throws IOException {
			throw new IOException("Legacy readBody must not be silently bypassed");
		}
	}

	public static class InjectableList
			extends ArrayList<String>
			implements PojoInjectable {
		public InjectableList() {
			EVENTS.add("list.new");
		}

		@Override
		public void beforeSerialize() {
			EVENTS.add("list.beforeS");
			this.add("prepared");
		}

		@Override
		public void afterSerialize() {
			EVENTS.add("list.afterS");
		}

		@Override
		public void beforeDeserialize() {
			EVENTS.add("list.beforeD:" + this.size());
		}

		@Override
		public void afterDeserialize() {
			EVENTS.add("list.afterD:" + this.size());
		}
	}

	static class ListOwner {
		@PojoCodec(emptyPolicy = com.inzent.apim.utilities.pojocodec.annotation.EmptyPolicy.INCLUDE)
		InjectableList list;
	}

	@BeforeEach
	void reset() {
		EVENTS.clear();
		failure = null;
		beforeWriteFailure = null;
		afterWriteFailure = null;
		beforeReadFailure = null;
		afterReadFailure = null;
		failureHookFailure = null;
	}

	private byte[] bytes(Object bean) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new PojoCodecController().serialize(out,
		                                    bean);
		return out.toByteArray();
	}

	@Test
	void successHooksSurroundFieldAccessAndCreation() throws Exception {
		Bean bean = new Bean();
		EVENTS.clear();
		byte[] bytes = this.bytes(bean);
		Assertions.assertEquals(List.of("beforeS:seed",
		                                "afterS"),
		                        EVENTS);
		Assertions.assertEquals("after-write",
		                        bean.value);
		EVENTS.clear();
		Bean result = new PojoCodecController().deserialize(new ByteArrayInputStream(bytes),
		                                                    Bean.class);
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "afterD:prepared"),
		                        EVENTS);
		Assertions.assertEquals("prepared:after-read",
		                        result.value);
	}

	@Test
	void outputFailureRunsFailureInsteadOfCompletionAndKeepsPrimaryCause() {
		Bean bean = new Bean();
		EVENTS.clear();
		IOException primary = new IOException("output");
		RuntimeException secondary = new RuntimeException("failure hook");
		failureHookFailure = secondary;
		OutputStream broken = new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				throw primary;
			}
		};
		IOException thrown = Assertions.assertThrows(IOException.class,
		                                             () -> new PojoCodecController().serialize(broken,
		                                                                                       bean));
		Assertions.assertSame(primary,
		                      thrown);
		Assertions.assertSame(primary,
		                      failure);
		Assertions.assertArrayEquals(new Throwable[]{secondary},
		                             primary.getSuppressed());
		Assertions.assertEquals(List.of("beforeS:seed",
		                                "failS"),
		                        EVENTS);
	}

	@Test
	void beforeAndAfterHookErrorsAlsoTriggerFailureExactlyOnce() {
		Bean bean = new Bean();
		EVENTS.clear();
		RuntimeException primary = new RuntimeException("before");
		beforeWriteFailure = primary;
		Assertions.assertSame(primary,
		                      Assertions.assertThrows(RuntimeException.class,
		                                              () -> this.bytes(bean)));
		Assertions.assertEquals(List.of("beforeS:seed",
		                                "failS"),
		                        EVENTS);
		beforeWriteFailure = null;
		afterWriteFailure = primary;
		EVENTS.clear();
		Assertions.assertSame(primary,
		                      Assertions.assertThrows(RuntimeException.class,
		                                              () -> this.bytes(bean)));
		Assertions.assertEquals(List.of("beforeS:seed",
		                                "afterS",
		                                "failS"),
		                        EVENTS);
	}

	@Test
	void nestedPojoAndCollectionElementsHaveTheirOwnLifecycles() throws Exception {
		Parent parent = new Parent();
		EVENTS.clear();
		byte[] encoded = this.bytes(parent);
		Assertions.assertEquals(List.of("parent.beforeS",
		                                "beforeS:seed",
		                                "afterS",
		                                "parent.afterS"),
		                        EVENTS);
		EVENTS.clear();
		new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                      Parent.class);
		Assertions.assertEquals(List.of("new",
		                                "parent.beforeD",
		                                "new",
		                                "beforeD:seed",
		                                "afterD:prepared",
		                                "parent.afterD"),
		                        EVENTS);
		PlainParent plain = new PlainParent();
		plain.items.add(new Bean());
		EVENTS.clear();
		encoded = this.bytes(plain);
		Assertions.assertEquals(List.of("beforeS:seed",
		                                "afterS"),
		                        EVENTS);
		EVENTS.clear();
		new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                      PlainParent.class);
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "afterD:prepared"),
		                        EVENTS);
	}

	@Test
	void rootFailureAfterInstanceCreationAndStrictTrailingInputDoNotComplete() throws Exception {
		byte[] encoded = this.bytes(new Bean());
		EVENTS.clear();
		byte[] trailing = Arrays.copyOf(encoded,
		                                encoded.length + 1);
		Assertions.assertThrows(IOException.class,
		                        () -> new PojoCodecController().deserializeSingle(new ByteArrayInputStream(trailing),
		                                                                          Bean.class));
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "failD"),
		                        EVENTS);
		EVENTS.clear();
		beforeReadFailure = new RuntimeException("before read");
		Assertions.assertThrows(IOException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                                                    Bean.class));
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "failD"),
		                        EVENTS);
		Assertions.assertSame(beforeReadFailure,
		                      failure);
	}

	@Test
	void afterDeserializeErrorIsReportedAndFailureHookErrorIsSuppressed() throws Exception {
		byte[] encoded = this.bytes(new Bean());
		EVENTS.clear();
		afterReadFailure = new RuntimeException("after read");
		failureHookFailure = new RuntimeException("secondary");
		RuntimeException thrown = Assertions.assertThrows(RuntimeException.class,
		                                                  () -> new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                                                                              Bean.class));
		Assertions.assertSame(afterReadFailure,
		                      thrown);
		Assertions.assertSame(thrown,
		                      failure);
		Assertions.assertArrayEquals(new Throwable[]{failureHookFailure},
		                             thrown.getSuppressed());
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "afterD:prepared",
		                                "failD"),
		                        EVENTS);
	}

	@Test
	void failuresBeforeConstructionAndNullRootsHaveNoInstanceHooks() throws Exception {
		Assertions.assertThrows(IOException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(new byte[]{1,
		                                                                                                        2}),
		                                                                    Bean.class));
		Assertions.assertTrue(EVENTS.isEmpty());
		Assertions.assertNull(PojoCodecControllerTest.copy(new PojoCodecController(),
		                                                   Bean.class,
		                                                   null));
		Assertions.assertTrue(EVENTS.isEmpty());
	}

	@Test
	void fieldApiUsesExistingOwnerAndCallbacksWrapSingleField() throws Exception {
		Bean source = new Bean();
		EVENTS.clear();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PojoCodecController codec = new PojoCodecController();
		codec.serialize(out,
		                source,
		                Bean.class.getDeclaredField("value"));
		Assertions.assertEquals(List.of("beforeS:seed",
		                                "afterS"),
		                        EVENTS);
		Bean target = new Bean();
		EVENTS.clear();
		codec.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                  target,
		                  Bean.class.getDeclaredField("value"));
		Assertions.assertEquals(List.of("beforeD:seed",
		                                "afterD:prepared"),
		                        EVENTS);
		Assertions.assertEquals("prepared:after-read",
		                        target.value);
	}

	@Test
	void injectableRuntimeSubclassUnderProjectedRootIsNotCalledTwice() throws Exception {
		InjectableSubclass source = new InjectableSubclass();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new PojoCodecController().serialize(out,
		                                    Base.class,
		                                    source);
		Assertions.assertEquals(List.of("project.before",
		                                "project.after"),
		                        EVENTS);
		Assertions.assertEquals("prepared-base",
		                        new PojoCodecController().deserialize(new ByteArrayInputStream(out.toByteArray()),
		                                                              Base.class).value);
	}

	@Test
	void customTypeCodecCreatesBeforeHookAndPopulatesTheSameInstance() throws Exception {
		byte[] encoded = this.bytes(new CustomBean());
		EVENTS.clear();
		CustomBean result = new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                                          CustomBean.class);
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "codec.read:before-read",
		                                "afterD:prepared"),
		                        EVENTS);
		Assertions.assertEquals("prepared:after-read",
		                        result.value);
	}

	@Test
	void legacyDefaultCodecSubclassMustExplicitlyProvideItsInjectableReader() throws Exception {
		byte[] encoded = this.bytes(new LegacyBean());
		EVENTS.clear();
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                                                    LegacyBean.class));
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "failD"),
		                        EVENTS);
	}

	@Test
	void injectableContainerSubclassesAreCreatedBeforePopulation() throws Exception {
		InjectableList list = new InjectableList();
		EVENTS.clear();
		byte[] encoded = this.bytes(list);
		Assertions.assertEquals(List.of("list.beforeS",
		                                "list.afterS"),
		                        EVENTS);
		EVENTS.clear();
		InjectableList result = new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                                              InjectableList.class);
		Assertions.assertEquals(List.of("list.new",
		                                "list.beforeD:0",
		                                "list.afterD:1"),
		                        EVENTS);
		Assertions.assertEquals(List.of("prepared"),
		                        result);
		ListOwner owner = new ListOwner();
		owner.list = new InjectableList();
		EVENTS.clear();
		encoded = this.bytes(owner);
		Assertions.assertEquals(List.of("list.beforeS",
		                                "list.afterS"),
		                        EVENTS);
		EVENTS.clear();
		new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                      ListOwner.class);
		Assertions.assertEquals(List.of("list.new",
		                                "list.beforeD:0",
		                                "list.afterD:1"),
		                        EVENTS);
	}

	@Test
	void nestedFailureNotifiesChildThenParentAndErrorsArePreserved() {
		Parent parent = new Parent();
		EVENTS.clear();
		beforeWriteFailure = new RuntimeException("nested before");
		Assertions.assertThrows(IOException.class,
		                        () -> this.bytes(parent));
		Assertions.assertEquals(List.of("parent.beforeS",
		                                "beforeS:seed",
		                                "failS",
		                                "parent.failS"),
		                        EVENTS);
		beforeWriteFailure = null;
		AssertionError error = new AssertionError("hook error");
		Bean bean = new Bean() {
			@Override
			public void beforeSerialize() {
				throw error;
			}
		};
		EVENTS.clear();
		Assertions.assertSame(error,
		                      Assertions.assertThrows(AssertionError.class,
		                                              () -> this.bytes(bean)));
		Assertions.assertSame(error,
		                      failure);
		Assertions.assertEquals(List.of("failS"),
		                        EVENTS);
	}

	@Test
	void truncatedBodyNotifiesCreatedInstanceOnce() throws Exception {
		byte[] encoded = this.bytes(new Bean());
		EVENTS.clear();
		Assertions.assertThrows(IOException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(Arrays.copyOf(encoded,
		                                                                                                           encoded.length - 1)),
		                                                                    Bean.class));
		Assertions.assertEquals(List.of("new",
		                                "beforeD:seed",
		                                "failD"),
		                        EVENTS);
	}

	@Test
	void nestedValueBoundaryFailureDoesNotRunCompletionBeforeFailure() throws Exception {
		byte[] original = this.bytes(new Parent());
		ByteArrayInputStream cursor = new ByteArrayInputStream(original);
		DataInputStream in = new DataInputStream(cursor);
		in.skipNBytes(17);
		in.skipNBytes(in.readInt());
		in.skipNBytes(in.readInt());
		in.readInt();
		int parentBody = original.length - cursor.available();
		in.readInt();
		in.readInt();
		in.skipNBytes(in.readInt());
		in.readByte();
		in.skipNBytes(in.readInt());
		in.readInt();
		int fieldBody = original.length - cursor.available();
		in.readInt();
		int childFrame = original.length - cursor.available();
		byte[] malformed = Arrays.copyOf(original,
		                                 original.length + 1);
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(malformed);
		for (int offset : new int[]{8,
		                            13,
		                            parentBody,
		                            fieldBody,
		                            childFrame + 1}) {
			buffer.putInt(offset,
			              buffer.getInt(offset) + 1);
		}
		EVENTS.clear();
		Assertions.assertThrows(IOException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(malformed),
		                                                                    Parent.class));
		Assertions.assertEquals(List.of("new",
		                                "parent.beforeD",
		                                "new",
		                                "beforeD:seed",
		                                "failD",
		                                "parent.failD"),
		                        EVENTS);
	}

	@Test
	void injectableEnumsUseTheResolvedConstantForRootAndNestedHooks() throws Exception {
		byte[] encoded = this.bytes(InjectableEnum.VALUE);
		Assertions.assertEquals(List.of("enum.beforeS",
		                                "enum.afterS"),
		                        EVENTS);
		EVENTS.clear();
		Assertions.assertSame(InjectableEnum.VALUE,
		                      new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                                            InjectableEnum.class));
		Assertions.assertEquals(List.of("enum.beforeD",
		                                "enum.afterD"),
		                        EVENTS);
		EVENTS.clear();
		encoded = this.bytes(new EnumOwner());
		EVENTS.clear();
		new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                      EnumOwner.class);
		Assertions.assertEquals(List.of("enum.beforeD",
		                                "enum.afterD"),
		                        EVENTS);
	}

	@Test
	void injectableEnumHookFailureIsReportedOnce() throws Exception {
		byte[] encoded = this.bytes(InjectableEnum.VALUE);
		EVENTS.clear();
		beforeReadFailure = new RuntimeException("enum before");
		Assertions.assertThrows(IOException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(encoded),
		                                                                    InjectableEnum.class));
		Assertions.assertEquals(List.of("enum.beforeD",
		                                "enum.failD"),
		                        EVENTS);
		Assertions.assertSame(beforeReadFailure,
		                      failure);
	}

	@Test
	void dynamicInjectableChildInCompiledParentIsNotCalledDuringSpeculativeFallback() throws Exception {
		PojoCodecController codec = PojoCodecController.builder()
		                                               .registerType("dynamic",
		                                                             InjectableSubclass.class)
		                                               .build();
		DynamicParent source = new DynamicParent();
		source.child = new InjectableSubclass();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.serialize(out,
		                source);
		Assertions.assertEquals(List.of("project.before",
		                                "project.after"),
		                        EVENTS);
		EVENTS.clear();
		DynamicParent result = codec.deserialize(new ByteArrayInputStream(out.toByteArray()),
		                                         DynamicParent.class);
		Assertions.assertEquals(List.of("project.beforeD",
		                                "project.afterD"),
		                        EVENTS);
		Assertions.assertEquals("prepared-base",
		                        result.child.value);
	}
}
