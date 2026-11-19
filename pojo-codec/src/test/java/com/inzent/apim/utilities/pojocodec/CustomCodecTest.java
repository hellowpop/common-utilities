package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.annotation.PojoCodec;
import com.inzent.apim.utilities.pojocodec.codec.*;
import java.io.*;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CustomCodecTest {
    @PojoCodec(typeCodec = ImmutableCodec.class)
    static class Immutable { final String value; Immutable(String value) { this.value = value; } }
    public static class ImmutableCodec extends AbstractTypeCodec<Immutable> {
        @Override public void writeBody(OutputStream out, Immutable bean, EncodeContext context) throws IOException { context.writeValue(out, bean.value, String.class); }
        @Override public Immutable readBody(InputStream in, Class<Immutable> type, DecodeContext context) throws IOException { return new Immutable((String) context.readValue(in, String.class)); }
    }
    static class FieldBean { @PojoCodec(fieldCodec = UpperFieldCodec.class) String value = "hello"; }
    public static class UpperFieldCodec extends AbstractFieldCodec {
        @Override public void writeField(OutputStream out, Object bean, Field field, EncodeContext context) throws IOException {
            try { context.writeValue(out, ((String) field.get(bean)).toUpperCase(java.util.Locale.ROOT), String.class); }
            catch (IllegalAccessException exception) { throw new IOException(exception); }
        }
        @Override public void readField(InputStream in, Object bean, Field field, DecodeContext context) throws IOException {
            try { field.set(bean, context.readValue(in, String.class)); } catch (IllegalAccessException exception) { throw new IOException(exception); }
        }
    }
    @Test void classAndFieldCodecsCanOverridePayload() throws Exception {
        PojoCodecController codec = new PojoCodecController();
        Assertions.assertEquals("x", PojoCodecControllerTest.copy(codec, Immutable.class, new Immutable("x")).value);
        Assertions.assertEquals("HELLO", PojoCodecControllerTest.copy(codec, FieldBean.class, new FieldBean()).value);
    }
    public static class V2Codec extends ImmutableCodec {
        @Override public boolean supportsVersion(int version) { return version == 1 || version == 2; }
    }
    @Test void registrationRejectsAnUnreadableCurrentVersion() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> PojoCodecController.builder().registerCodec("immutable", 2, new ImmutableCodec()).build());
    }
    @PojoCodec(typeCodec = PartialCodec.class) static class Partial { }
    public static class PartialCodec extends AbstractTypeCodec<Partial> {
        public void writeBody(OutputStream out, Partial bean, EncodeContext context) throws IOException { out.write(1); }
        public Partial readBody(InputStream in, Class<Partial> type, DecodeContext context) { return new Partial(); }
    }
    @Test void customCodecMustConsumeItsWholeBody() {
        Assertions.assertThrows(PojoCodecException.class, () -> PojoCodecControllerTest.copy(new PojoCodecController(), Partial.class, new Partial()));
    }
}
