package com.inzent.apim.utilities.pojocodec;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WireFixtureTest {
	static byte[] fixture(String name) throws IOException {
		try (InputStream in = WireFixtureTest.class.getResourceAsStream("/compatibility/" + name)) {
			return HexFormat.of()
			                .parseHex(new String(Objects.requireNonNull(in)
			                                            .readAllBytes(),
			                                     StandardCharsets.US_ASCII).replaceAll("\\s",
			                                                                           ""));
		}
	}

	@Test
	void frozenStringDocumentReadsAndWritesExactly() throws Exception {
		byte[] expected = fixture("v1-minimal.hex");
		PojoCodecController codec = new PojoCodecController();
		Assertions.assertEquals("x",
		                        codec.deserialize(new ByteArrayInputStream(expected),
		                                          String.class));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.serialize(out,
		                "x");
		Assertions.assertArrayEquals(expected,
		                             out.toByteArray());
	}

	@Test
	void frozenContainerDocumentReadsAndWritesExactly() throws Exception {
		byte[] expected = fixture("v1-container.hex");
		PojoCodecController codec = new PojoCodecController();
		Assertions.assertEquals(List.of(1),
		                        codec.deserialize(new ByteArrayInputStream(expected),
		                                          List.class));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.serialize(out,
		                new ArrayList<>(List.of(1)));
		Assertions.assertArrayEquals(expected,
		                             out.toByteArray());
	}
}
