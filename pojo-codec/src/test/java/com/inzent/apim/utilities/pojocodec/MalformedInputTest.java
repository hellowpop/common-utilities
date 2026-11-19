package com.inzent.apim.utilities.pojocodec;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MalformedInputTest {
	static class Primitive {
		int n;
	}

	@Test
	void everyTruncatedPrefixIsRejected() throws Exception {
		byte[] bytes = WireFixtureTest.fixture("v1-minimal.hex");
		PojoCodecController codec = new PojoCodecController();
		for (int size = 0; size < bytes.length; size++) {
			byte[] prefix = Arrays.copyOf(bytes,
			                              size);
			Assertions.assertThrows(PojoCodecException.class,
			                        () -> codec.deserialize(new ByteArrayInputStream(prefix),
			                                                String.class));
		}
	}

	@Test
	void unknownVersionsKindsFlagsAndBadLengthsAreRejected() throws Exception {
		byte[] original = WireFixtureTest.fixture("v1-minimal.hex");
		PojoCodecController codec = new PojoCodecController();
		int[] positions = {0,
		                   5,
		                   7,
		                   8,
		                   11,
		                   12,
		                   13,
		                   16};
		for (int position : positions) {
			byte[] malformed = original.clone();
			malformed[position] = (byte) 255;
			Assertions.assertThrows(PojoCodecException.class,
			                        () -> codec.deserialize(new ByteArrayInputStream(malformed),
			                                                String.class));
		}
	}

	@Test
	void primitiveNullRecordAndMalformedUtf8AreRejected() throws Exception {
		// name='n', state=NULL, empty codec ID, version=0, bodyLength=0.
		byte[] nullField = HexFormat.of()
		                            .parseHex("000000016e01000000000000000000000000");
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(nullField),
		                                                                    new Primitive(),
		                                                                    Primitive.class.getDeclaredField("n")));
		byte[] invalid = WireFixtureTest.fixture("v1-minimal.hex");
		invalid[17] = (byte) 255;
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> new PojoCodecController().deserialize(new ByteArrayInputStream(invalid),
		                                                                    String.class));
	}

	@Test
	void explicitSingleDocumentModeRejectsTrailingData() throws Exception {
		byte[] bytes = Arrays.copyOf(WireFixtureTest.fixture("v1-minimal.hex"),
		                             19);
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> new PojoCodecController().deserializeSingle(new ByteArrayInputStream(bytes),
		                                                                          String.class));
	}
}
