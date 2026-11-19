package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.codec.value.TemporalValueCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TemporalValueCodecTest {
    private Object roundTrip(Object value, boolean zone) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TemporalValueCodec.write(out, value, zone);
        return TemporalValueCodec.read(new ByteArrayInputStream(out.toByteArray()), 4096);
    }
    @Test void truncatesSubMillisecondPrecisionIncludingNegativeEpoch() throws Exception {
        Assertions.assertEquals(-1L, ((Instant) roundTrip(Instant.ofEpochSecond(-1, 999999999), false)).toEpochMilli());
        Timestamp timestamp = Timestamp.from(Instant.ofEpochSecond(0, 123456789));
        Assertions.assertEquals(123000000, ((Timestamp) roundTrip(timestamp, false)).getNanos());
        Assertions.assertEquals(LocalTime.of(1, 2, 3, 456000000), roundTrip(LocalTime.of(1, 2, 3, 456789123), false));
    }
    @Test void localValuesAndZonesDoNotDependOnDefaultZone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Assertions.assertEquals(LocalDate.of(1960, 1, 1), roundTrip(LocalDate.of(1960, 1, 1), false));
            ZonedDateTime date = ZonedDateTime.parse("2024-11-03T01:30:00-04:00[America/New_York]");
            Assertions.assertEquals(date, roundTrip(date, true));
            Assertions.assertEquals("Z", ((ZonedDateTime) roundTrip(date, false)).getZone().getId());
        } finally { TimeZone.setDefault(original); }
    }
    @Test void sqlTimeRetainsItsMillisecondFraction() throws Exception {
        java.sql.Time value = java.sql.Time.valueOf("12:34:56"); value.setTime(value.getTime() + 456);
        java.sql.Time result = (java.sql.Time) roundTrip(value, false);
        Assertions.assertEquals(456, Math.floorMod(result.getTime(), 1000));
    }
}
