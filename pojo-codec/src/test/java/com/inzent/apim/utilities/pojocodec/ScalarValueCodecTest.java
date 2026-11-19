package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.codec.value.ScalarValueCodec;
import com.inzent.apim.utilities.pojocodec.internal.Session;
import com.inzent.apim.utilities.pojocodec.registry.TypeRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ScalarValueCodecTest {
    @Test void scalarsRoundTripWithoutImplicitConversion() throws Exception {
        TypeRegistry types = new TypeRegistry(Map.of());
        Object[] values = {true, (byte)-1, (short) 300, 77, -9L, -0.0f, Double.POSITIVE_INFINITY, '한', "한😀",
                new BigInteger("123456789012345678901234567890"), new BigDecimal("123.4500")};
        for (Object value : values) {
            Session session = new Session(CodecLimits.defaults());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int kind = ScalarValueCodec.kind(value.getClass());
            ScalarValueCodec.write(kind, out, value, types, session);
            Object decoded = ScalarValueCodec.read(kind, new ByteArrayInputStream(out.toByteArray()), types, session);
            Assertions.assertEquals(value, decoded);
        }
    }
    @Test void rejectsInvalidBoolean() {
        Assertions.assertThrows(PojoCodecException.class, () -> ScalarValueCodec.read(1, new ByteArrayInputStream(new byte[]{2}),
                new TypeRegistry(Map.of()), new Session(CodecLimits.defaults())));
    }
}
