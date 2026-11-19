package com.inzent.apim.utilities.pojocodec.codec.value;

import com.inzent.apim.utilities.pojocodec.internal.FrameIO;
import com.inzent.apim.utilities.pojocodec.internal.Session;
import com.inzent.apim.utilities.pojocodec.registry.TypeRegistry;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Scalar payloads; outer value framing belongs to the engine.
 */
public final class ScalarValueCodec {
	private ScalarValueCodec() {
	}

	public static int kind(Class<?> type) {
		if (type == Boolean.class || type == boolean.class) {
			return 1;
		}
		if (type == Byte.class || type == byte.class) {
			return 2;
		}
		if (type == Short.class || type == short.class) {
			return 3;
		}
		if (type == Integer.class || type == int.class) {
			return 4;
		}
		if (type == Long.class || type == long.class) {
			return 5;
		}
		if (type == Float.class || type == float.class) {
			return 6;
		}
		if (type == Double.class || type == double.class) {
			return 7;
		}
		if (type == Character.class || type == char.class) {
			return 8;
		}
		if (type == String.class) {
			return 9;
		}
		if (type == BigInteger.class) {
			return 10;
		}
		if (type == BigDecimal.class) {
			return 11;
		}
		if (type.isEnum() || Enum.class.isAssignableFrom(type)) {
			return 12;
		}
		return -1;
	}

	public static void write(int kind,
	                         OutputStream stream,
	                         Object value,
	                         TypeRegistry types,
	                         Session session) throws IOException {
		DataOutputStream out = new DataOutputStream(stream);
		switch (kind) {
			case 1 -> out.writeBoolean((Boolean) value);
			case 2 -> out.writeByte((Byte) value);
			case 3 -> out.writeShort((Short) value);
			case 4 -> out.writeInt((Integer) value);
			case 5 -> out.writeLong((Long) value);
			case 6 -> out.writeInt(Float.floatToRawIntBits((Float) value));
			case 7 -> out.writeLong(Double.doubleToRawLongBits((Double) value));
			case 8 -> out.writeChar((Character) value);
			case 9 -> {
				String text = (String) value;
				if (text.length() > session.limits.stringBytes()) {
					throw session.error("String too long");
				}
				ByteArrayOutputStream encoded = new ByteArrayOutputStream();
				FrameIO.writeString(encoded,
				                    text);
				byte[] bytes = encoded.toByteArray();
				if (bytes.length - 4 > session.limits.stringBytes()) {
					throw session.error("String too long");
				}
				out.write(bytes,
				          4,
				          bytes.length - 4);
			}
			case 10 -> out.write(((BigInteger) value).toByteArray());
			case 11 -> {
				BigDecimal number = (BigDecimal) value;
				byte[] bytes = number.unscaledValue()
				                     .toByteArray();
				out.writeInt(number.scale());
				out.writeInt(bytes.length);
				out.write(bytes);
			}
			case 12 -> {
				Enum<?> enumeration = (Enum<?>) value;
				FrameIO.writeString(out,
				                    types.id(enumeration.getDeclaringClass()));
				FrameIO.writeString(out,
				                    enumeration.name());
			}
			default -> throw session.error("Unsupported scalar kind: " + kind);
		}
	}

	@SuppressWarnings({"rawtypes",
	                   "unchecked"})
	public static Object read(int kind,
	                          InputStream stream,
	                          TypeRegistry types,
	                          Session session) throws IOException {
		DataInputStream in = new DataInputStream(stream);
		return switch (kind) {
			case 1 -> {
				int value = in.readUnsignedByte();
				if (value > 1) {
					throw session.error("Invalid boolean");
				}
				yield value == 1;
			}
			case 2 -> in.readByte();
			case 3 -> in.readShort();
			case 4 -> in.readInt();
			case 5 -> in.readLong();
			case 6 -> Float.intBitsToFloat(in.readInt());
			case 7 -> Double.longBitsToDouble(in.readLong());
			case 8 -> in.readChar();
			case 9 -> FrameIO.decodeUtf8(readRemaining(in,
			                                           session.limits.stringBytes(),
			                                           session));
			case 10 -> {
				byte[] bytes = readRemaining(in,
				                             session.limits.frameBytes(),
				                             session);
				if (bytes.length == 0) {
					throw session.error("Empty integer");
				}
				yield new BigInteger(bytes);
			}
			case 11 -> {
				int scale = in.readInt();
				int size = FrameIO.length(in,
				                          session.limits.frameBytes());
				if (size == 0) {
					throw session.error("Empty unscaled integer");
				}
				yield new BigDecimal(new BigInteger(FrameIO.readExact(in,
				                                                      size)),
				                     scale);
			}
			case 12 -> {
				Class<?> type = types.resolve(FrameIO.readString(in,
				                                                 session.limits.identifierBytes()));
				if (!type.isEnum()) {
					throw session.error("Expected enum type");
				}
				yield Enum.valueOf((Class) type,
				                   FrameIO.readString(in,
				                                      session.limits.identifierBytes()));
			}
			default -> throw session.error("Unknown scalar kind: " + kind);
		};
	}

	private static byte[] readRemaining(InputStream in,
	                                    int maximum,
	                                    Session session) throws IOException {
		// Payload stream is bounded by the common framing layer.
		byte[] bytes = in.readNBytes(maximum);
		if (in.read() != -1) {
			throw session.error("Payload exceeds limit");
		}
		return bytes;
	}
}
