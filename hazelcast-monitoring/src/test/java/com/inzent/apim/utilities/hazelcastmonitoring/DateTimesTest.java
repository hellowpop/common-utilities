package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.time.DateTimes;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DateTimesTest {
    @Test void formatsDatesInSeoulAndSupportsReportZone() {
        var instant=Instant.parse("2026-10-27T06:57:19.123456Z");
        assertThat(DateTimes.format(instant)).isEqualTo("2026-10-27 15:57:19");
        assertThat(DateTimes.format(instant,ZoneOffset.UTC)).isEqualTo("2026-10-27 06:57:19");
        assertThat(DateTimes.format(null)).isNull();
    }
    @Test void parsesLocalAndLegacyOffsetDatesStrictly() {
        assertThat(DateTimes.parse("2026-10-27 15:57:19")).isEqualTo(Instant.parse("2026-10-27T06:57:19Z"));
        assertThat(DateTimes.parse("2026-10-27T15:57:19.123456+09:00")).isEqualTo(Instant.parse("2026-10-27T06:57:19.123456Z"));
        assertThatThrownBy(() -> DateTimes.parse("2026-02-30 15:57:19")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DateTimes.parse("2026-10-27T15:57:19")).isInstanceOf(IllegalArgumentException.class);
    }
}
