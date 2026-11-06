package com.inzent.apim.utilities.http.snappy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnappyStreamExampleTest {
    @Test void rawAndFramedRoundTrips() throws Exception {
        for (int size : new int[]{0,1,61,65536,180000}) {
            byte[] original=new byte[size]; new Random(42).nextBytes(original);
            for (boolean framed : new boolean[]{false,true}) {
                ByteArrayOutputStream encoded=new ByteArrayOutputStream();
                if (framed) SnappyStreamExample.compressFramed(new ByteArrayInputStream(original),encoded);
                else SnappyStreamExample.compress(new ByteArrayInputStream(original),encoded,size);
                ByteArrayOutputStream decoded=new ByteArrayOutputStream();
                if (framed) SnappyStreamExample.decompressFramed(new ByteArrayInputStream(encoded.toByteArray()),decoded);
                else SnappyStreamExample.decompress(new ByteArrayInputStream(encoded.toByteArray()),decoded);
                assertArrayEquals(original,decoded.toByteArray());
            }
        }
    }
    @Test void decodesKnownRawBlock() throws Exception {
        ByteArrayOutputStream decoded=new ByteArrayOutputStream();
        SnappyStreamExample.decompress(new ByteArrayInputStream(new byte[]{5,16,104,101,108,108,111}),decoded);
        assertEquals("hello",decoded.toString(StandardCharsets.UTF_8));
    }
    @Test void compressesRepeatedContent() throws Exception {
        byte[] original="snappy stream example\n".repeat(10000).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        SnappyStreamExample.compress(new ByteArrayInputStream(original),encoded,original.length);
        assertTrue(encoded.size()<original.length/4);
    }
    @Test void rejectsWrongSizeAndTruncatedInput() {
        for (long size : new long[]{0,2}) assertThrows(IOException.class,()->
            SnappyStreamExample.compress(new ByteArrayInputStream(new byte[]{1}),new ByteArrayOutputStream(),size));
        assertThrows(IllegalArgumentException.class,()->
            SnappyStreamExample.compress(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),-1));
        assertThrows(IOException.class,()->
            SnappyStreamExample.decompress(new ByteArrayInputStream(new byte[]{5,16,104}),new ByteArrayOutputStream()));
    }
    @Test void closesBothOwnedStreams() throws Exception {
        class Source extends ByteArrayInputStream { boolean closed; Source(){super(new byte[]{1});} public void close(){closed=true;} }
        class Target extends ByteArrayOutputStream { boolean closed; public void close(){closed=true;} }
        Source source=new Source(); Target target=new Target();
        SnappyStreamExample.compress(source,target,1);
        assertTrue(source.closed); assertTrue(target.closed);
    }
}
