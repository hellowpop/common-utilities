package com.inzent.apim.utilities.pojocodec;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PojoCodecControllerTest {
    static class Base { int number; }
    static class Bean extends Base { String name; List<List<String>> nested; int[][] grid; Bean child; transient int ignored; }
    static <T> T copy(PojoCodecController codec, Class<T> type, T bean) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); codec.serialize(out, type, bean);
        return codec.deserialize(new ByteArrayInputStream(out.toByteArray()), type);
    }
    @Test void nestedPojoArraysAndContainersRoundTrip() throws Exception {
        PojoCodecController codec = new PojoCodecController(); Bean bean = new Bean();
        bean.number = 7; bean.name = "한😀"; bean.nested = List.of(List.of("a", "b")); bean.grid = new int[][]{{1, 2}, {3}};
        Bean result = copy(codec, Bean.class, bean);
        Assertions.assertEquals(7, result.number); Assertions.assertEquals("한😀", result.name);
        Assertions.assertEquals(bean.nested, result.nested); Assertions.assertArrayEquals(new int[]{1, 2}, result.grid[0]);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> result.nested.clear());
    }
    @Test void declaredRootProjectsOnlyBaseAndDocumentsRemainSeparate() throws Exception {
        PojoCodecController codec = new PojoCodecController(); Bean bean = new Bean(); bean.name = "excluded"; bean.number = 9;
        ByteArrayOutputStream out = new ByteArrayOutputStream(); codec.serialize(out, Base.class, bean); codec.serialize(out, "second");
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Assertions.assertEquals(9, codec.deserialize(in, Base.class).number); Assertions.assertEquals("second", codec.deserialize(in, String.class));
        Assertions.assertEquals(-1, in.read());
    }
    @Test void cyclesFailButSharedReferencesCanBeRepeated() throws Exception {
        Bean bean = new Bean(); bean.child = bean;
        Assertions.assertThrows(PojoCodecException.class, () -> copy(new PojoCodecController(), Bean.class, bean));
    }
    @Test void callerStreamsAreNotClosedOrFlushed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override public void close() { Assertions.fail("close called"); }
            @Override public void flush() { Assertions.fail("flush called"); }
        };
        PojoCodecController codec = new PojoCodecController(); codec.serialize(out, "x");
        Assertions.assertEquals("x", codec.deserialize(new ByteArrayInputStream(out.toByteArray()), String.class));
    }
}
