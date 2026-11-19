package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.internal.BoundedInputStream;
import com.inzent.apim.utilities.pojocodec.internal.FrameIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FrameIOTest {
	@Test
	void shortReadsAndZeroSkipPreserveNextFrame() throws Exception {
		ByteArrayInputStream parent = new ByteArrayInputStream(new byte[]{1,
		                                                                  2,
		                                                                  3,
		                                                                  4}) {
			@Override
			public int read(byte[] b,
			                int off,
			                int len) {
				return super.read(b,
				                  off,
				                  Math.min(1,
				                           len));
			}

			@Override
			public long skip(long n) {
				return 0;
			}
		};
		BoundedInputStream bounded = new BoundedInputStream(parent,
		                                                    3);
		Assertions.assertArrayEquals(new byte[]{1,
		                                        2},
		                             FrameIO.readExact(bounded,
		                                               2));
		FrameIO.skipExact(bounded,
		                  1);
		Assertions.assertEquals(-1,
		                        bounded.read());
		bounded.close();
		Assertions.assertEquals(4,
		                        parent.read());
	}

	@Test
	void rejectsTruncationAndNegativeLengths() {
		Assertions.assertThrows(IOException.class,
		                        () -> FrameIO.readExact(new ByteArrayInputStream(new byte[0]),
		                                                1));
		Assertions.assertThrows(IOException.class,
		                        () -> FrameIO.readExact(new ByteArrayInputStream(new byte[0]),
		                                                -1));
	}

	@Test
	void utf8IsStrictAndByteCounted() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FrameIO.writeString(out,
		                    "한😀");
		Assertions.assertEquals("한😀",
		                        FrameIO.readString(new ByteArrayInputStream(out.toByteArray()),
		                                           7));
		Assertions.assertThrows(IOException.class,
		                        () -> FrameIO.readString(new ByteArrayInputStream(new byte[]{0,
		                                                                                     0,
		                                                                                     0,
		                                                                                     1,
		                                                                                     (byte) 0xff}),
		                                                 4));
	}
}
