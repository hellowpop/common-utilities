package com.inzent.apim.utilities.hazelcastmonitoring.time;

import java.time.*;
import java.time.format.*;
import java.util.Locale;

/** Text timestamps use seconds; persistence retains the original Instant precision. */
public final class DateTimes {
    public static final ZoneId STORAGE_ZONE=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TEXT=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FILE=DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss",Locale.ROOT);
    private DateTimes() {}
    public static String format(Instant value) { return format(value,STORAGE_ZONE); }
    public static String format(Instant value,ZoneId zone) { return value==null?null:TEXT.format(value.atZone(zone)); }
    public static String fileName(Instant value,ZoneId zone) { return FILE.format(value.atZone(zone)); }
    public static Instant parse(String value) {
        if(value==null)return null;
        try {
            return value.contains(" ")?LocalDateTime.parse(value,TEXT).atZone(STORAGE_ZONE).toInstant():OffsetDateTime.parse(value).toInstant();
        } catch(DateTimeException ex) {
            throw new IllegalArgumentException("Time requires yyyy-MM-dd HH:mm:ss (Asia/Seoul) or ISO-8601 offset: "+value,ex);
        }
    }
}
