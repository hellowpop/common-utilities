package com.inzent.apim.utilities.pojocodec.internal;

import java.io.*;
import java.util.*;

/**
 * Version 1 metadata has a fixed order shared by all built-in container adapters.
 */
public record ContainerHeader(String kind,
                              String comparator,
                              String enumType,
                              int capacity,
                              boolean fair,
                              boolean accessOrder,
                              String checkedKey,
                              String checkedValue,
                              Map<String, String> defaults) {
	public ContainerHeader {
		defaults = Map.copyOf(defaults);
	}

	public void write(OutputStream stream,
	                  Session session) throws IOException {
		writeId(stream,
		        kind,
		        session);
		DataOutputStream out = new DataOutputStream(stream);
		out.writeInt(1);
		try (BudgetedBuffer metadata = new BudgetedBuffer(session,
		                                                  session.limits.frameBytes())) {
			DataOutputStream data = new DataOutputStream(metadata);
			writeId(data,
			        comparator,
			        session);
			writeId(data,
			        enumType,
			        session);
			data.writeInt(capacity);
			data.writeBoolean(fair);
			data.writeBoolean(accessOrder);
			writeId(data,
			        checkedKey,
			        session);
			writeId(data,
			        checkedValue,
			        session);
			data.writeInt(defaults.size());
			for (Map.Entry<String, String> entry : new TreeMap<>(defaults).entrySet()) {
				writeText(data,
				          entry.getKey(),
				          session);
				writeText(data,
				          entry.getValue(),
				          session);
			}
			out.writeInt(metadata.size());
			metadata.writeTo(out);
		}
	}

	public static ContainerHeader read(InputStream stream,
	                                   Session session) throws IOException {
		String kind = FrameIO.readString(stream,
		                                 session.limits.identifierBytes());
		DataInputStream in = new DataInputStream(stream);
		if (in.readInt() != 1) {
			throw session.error("Unknown container version");
		}
		BoundedInputStream metadata = new BoundedInputStream(stream,
		                                                     FrameIO.length(in,
		                                                                    session.limits.frameBytes()));
		DataInputStream data = new DataInputStream(metadata);
		String comparator = FrameIO.readString(data,
		                                       session.limits.identifierBytes());
		String enumeration = FrameIO.readString(data,
		                                        session.limits.identifierBytes());
		int capacity = data.readInt();
		int fair = data.readUnsignedByte(), access = data.readUnsignedByte();
		if (fair > 1 || access > 1 || capacity < 0) {
			throw session.error("Invalid container metadata");
		}
		String key = FrameIO.readString(data,
		                                session.limits.identifierBytes()), value = FrameIO.readString(data,
		                                                                                              session.limits.identifierBytes());
		int count = FrameIO.length(data,
		                           session.limits.containerItems());
		session.items(count);
		Map<String, String> defaults = new LinkedHashMap<>();
		for (int index = 0; index < count; index++) {
			String name = FrameIO.readString(data,
			                                 session.limits.stringBytes()), text = FrameIO.readString(data,
			                                                                                          session.limits.stringBytes());
			if (defaults.put(name,
			                 text) != null) {
				throw session.error("Duplicate Properties default");
			}
		}
		FrameIO.exhausted(metadata);
		return new ContainerHeader(kind,
		                           comparator,
		                           enumeration,
		                           capacity,
		                           fair == 1,
		                           access == 1,
		                           key,
		                           value,
		                           defaults);
	}

	public static void writeId(OutputStream out,
	                           String value,
	                           Session session) throws IOException {
		if (value.length() > session.limits.identifierBytes() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > session.limits.identifierBytes()) {
			throw session.error("Identifier too long");
		}
		FrameIO.writeString(out,
		                    value);
	}

	private static void writeText(OutputStream out,
	                              String value,
	                              Session session) throws IOException {
		if (value.length() > session.limits.stringBytes() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > session.limits.stringBytes()) {
			throw session.error("Properties string too long");
		}
		FrameIO.writeString(out,
		                    value);
	}
}
