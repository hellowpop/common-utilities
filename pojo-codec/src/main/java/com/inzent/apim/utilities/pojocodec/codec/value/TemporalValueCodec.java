package com.inzent.apim.utilities.pojocodec.codec.value;

import com.inzent.apim.utilities.pojocodec.PojoCodecException;
import com.inzent.apim.utilities.pojocodec.internal.FrameIO;

import java.io.*;
import java.time.*;
import java.util.Date;

/**
 * All temporal payloads store exactly one millisecond long, plus an optional zone.
 */
public final class TemporalValueCodec {
	private TemporalValueCodec() {
	}

	public static boolean supports(Class<?> type) {
		return type == Date.class || type == java.sql.Date.class || type == java.sql.Time.class || type == java.sql.Timestamp.class || type == Instant.class || type == LocalDate.class || type == LocalTime.class || type == LocalDateTime.class || type == OffsetDateTime.class || type == ZonedDateTime.class;
	}

	public static void write(OutputStream stream,
	                         Object value,
	                         boolean includeZone) throws IOException {
		DataOutputStream out = new DataOutputStream(stream);
		int kind;
		long millis;
		String zone = null;
		try {
			Class<?> type = value.getClass();
			if (type == java.sql.Date.class) {
				kind = 2;
				millis = ((java.sql.Date) value).toLocalDate()
				                                .atStartOfDay()
				                                .toInstant(ZoneOffset.UTC)
				                                .toEpochMilli();
			} else if (type == java.sql.Time.class) {
				kind = 3;
				java.sql.Time time = (java.sql.Time) value;
				millis = time.toLocalTime()
				             .toNanoOfDay() / 1000000 + Math.floorMod(time.getTime(),
				                                                      1000);
			} else if (type == java.sql.Timestamp.class) {
				kind = 4;
				millis = ((java.sql.Timestamp) value).toInstant()
				                                     .toEpochMilli();
			} else if (type == Date.class) {
				kind = 1;
				millis = ((Date) value).getTime();
			} else if (type == Instant.class) {
				kind = 5;
				millis = ((Instant) value).toEpochMilli();
			} else if (type == LocalDate.class) {
				kind = 6;
				millis = ((LocalDate) value).atStartOfDay()
				                            .toInstant(ZoneOffset.UTC)
				                            .toEpochMilli();
			} else if (type == LocalTime.class) {
				kind = 7;
				millis = ((LocalTime) value).toNanoOfDay() / 1000000;
			} else if (type == LocalDateTime.class) {
				kind = 8;
				millis = ((LocalDateTime) value).toInstant(ZoneOffset.UTC)
				                                .toEpochMilli();
			} else if (type == OffsetDateTime.class) {
				kind = 9;
				OffsetDateTime date = (OffsetDateTime) value;
				millis = date.toInstant()
				             .toEpochMilli();
				if (includeZone) {
					zone = date.getOffset()
					           .getId();
				}
			} else if (type == ZonedDateTime.class) {
				kind = 10;
				ZonedDateTime date = (ZonedDateTime) value;
				millis = date.toInstant()
				             .toEpochMilli();
				if (includeZone) {
					zone = date.getZone()
					           .getId();
				}
			} else {
				throw new PojoCodecException("Unsupported temporal type: " + type.getName());
			}
		}
		catch (DateTimeException | ArithmeticException exception) {
			throw new PojoCodecException("Temporal range exceeded",
			                             exception);
		}
		out.writeByte(kind);
		out.writeLong(millis);
		out.writeByte(zone == null
		              ? 0
		              : 1);
		if (zone != null) {
			FrameIO.writeString(out,
			                    zone);
		}
	}

	public static Object read(InputStream stream,
	                          int maxIdentifier) throws IOException {
		DataInputStream in = new DataInputStream(stream);
		int kind = in.readUnsignedByte();
		long millis = in.readLong();
		int present = in.readUnsignedByte();
		if (present > 1 || (present == 1 && kind != 9 && kind != 10)) {
			throw new PojoCodecException("Invalid temporal zone flag");
		}
		String zone = present == 1
		              ? FrameIO.readString(in,
		                                   maxIdentifier)
		              : "Z";
		try {
			if ((kind == 2 || kind == 6) && millis % 86400000L != 0) {
				throw new PojoCodecException("Expected UTC midnight");
			}
			if ((kind == 3 || kind == 7) && (millis < 0 || millis >= 86400000)) {
				throw new PojoCodecException("Invalid time of day");
			}
			return switch (kind) {
				case 1 -> new Date(millis);
				case 2 -> java.sql.Date.valueOf(Instant.ofEpochMilli(millis)
				                                       .atOffset(ZoneOffset.UTC)
				                                       .toLocalDate());
				case 3 -> {
					java.sql.Time time = java.sql.Time.valueOf(LocalTime.ofNanoOfDay(millis * 1000000));
					time.setTime(time.getTime() + millis % 1000);
					yield time;
				}
				case 4 -> java.sql.Timestamp.from(Instant.ofEpochMilli(millis));
				case 5 -> Instant.ofEpochMilli(millis);
				case 6 -> Instant.ofEpochMilli(millis)
				                 .atOffset(ZoneOffset.UTC)
				                 .toLocalDate();
				case 7 -> LocalTime.ofNanoOfDay(millis * 1000000);
				case 8 -> LocalDateTime.ofInstant(Instant.ofEpochMilli(millis),
				                                  ZoneOffset.UTC);
				case 9 -> OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis),
				                                   ZoneOffset.of(zone));
				case 10 -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis),
				                                   ZoneId.of(zone));
				default -> throw new PojoCodecException("Unknown temporal kind: " + kind);
			};
		}
		catch (DateTimeException | IllegalArgumentException exception) {
			throw new PojoCodecException("Invalid temporal value",
			                             exception);
		}
	}
}
