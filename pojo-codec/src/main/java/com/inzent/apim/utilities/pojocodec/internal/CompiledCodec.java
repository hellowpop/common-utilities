package com.inzent.apim.utilities.pojocodec.internal;

import com.inzent.apim.utilities.pojocodec.*;
import com.inzent.apim.utilities.pojocodec.annotation.*;
import com.inzent.apim.utilities.pojocodec.codec.DefaultFieldCodec;
import com.inzent.apim.utilities.pojocodec.codec.value.ScalarValueCodec;
import com.inzent.apim.utilities.pojocodec.metadata.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Compiles common default POJOs to allocation-free write plans without changing v1. */
public final class CompiledCodec {
    private static final int RETAINED_BYTES = 65536;
    private static final Object[] NO_ARGS = new Object[0];
    private static final UnsupportedPath UNSUPPORTED = new UnsupportedPath();
    private static final class UnsupportedPath extends RuntimeException {
        UnsupportedPath() { super(null, null, false, false); }
    }
    private record Candidate(ValuePlan plan, IOException error) { }
    private static final class ValuePlan {
        final Type type; final Class<?> raw; final int kind;
        byte[] prefix; FieldPlan[] fields; Constructor<?> constructor; ValuePlan element; CodecOptions options;
        ValuePlan(Type type, int kind) { this.type = type; this.raw = GenericTypeResolver.raw(type); this.kind = kind; }
    }
    private record FieldPlan(Field field, ValuePlan value, CodecOptions options, byte[] name, byte[] valueHeader, byte[] nullHeader, byte[] inlineHeader) { }
    private final Engine engine;
    private final ThreadLocal<Scratch> scratch = new ThreadLocal<>();
    private final ClassValue<Candidate> plans = new ClassValue<>() {
        @Override protected Candidate computeValue(Class<?> type) {
            try {
                if (type.isPrimitive() || type.isArray() || ScalarValueCodec.kind(type) >= 0 || type.getName().startsWith("java.")) { return new Candidate(null, null); }
                engine.prepare(type);
            } catch (IOException exception) { return new Candidate(null, exception); }
            try {
                ValuePlan plan = compile(type, new HashSet<>());
                return new Candidate(plan != null && plan.kind == 18 ? plan : null, null);
            } catch (UnsupportedPath unsupported) { return new Candidate(null, null); }
            catch (IOException unavailablePlan) { return new Candidate(null, null); }
        }
    };
    public CompiledCodec(Engine engine) { this.engine = engine; }
    private ValuePlan plan(Class<?> type) throws IOException {
        Candidate candidate = plans.get(type); if (candidate.error() != null) { throw candidate.error(); } return candidate.plan();
    }
    public boolean supports(Class<?> type) throws IOException { return plan(type) != null; }
    private ValuePlan compile(Type type, Set<Class<?>> active) throws IOException {
        Class<?> raw = GenericTypeResolver.raw(type); int scalar = ScalarValueCodec.kind(raw);
        if (scalar >= 1 && scalar <= 9) { return new ValuePlan(type, scalar); }
        if (scalar >= 10) { return null; }
        if (raw == byte[].class) { return new ValuePlan(type, 14); }
        if (raw.isArray() && raw.getComponentType().isPrimitive()) {
            ValuePlan value = new ValuePlan(type, 15); value.element = compile(raw.getComponentType(), active); value.prefix = id(engine.typeId(raw)); return value;
        }
        if ((raw == List.class || raw == ArrayList.class) && type instanceof ParameterizedType) {
            ValuePlan element = compile(GenericTypeResolver.argument(type, 0), active); if (element == null) { return null; }
            ValuePlan value = new ValuePlan(type, 16); value.element = element;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new ContainerHeader("array-list", "natural", "", 0, false, false, "", "", Map.of()).write(bytes, new Session(engine.limits));
            value.prefix = bytes.toByteArray(); return value;
        }
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers()) || raw.getName().startsWith("java.") || !active.add(raw)) { return null; }
        try {
            if (!engine.defaultPojo(raw)) { return null; }
            ClassMetadata metadata = MetadataResolver.resolve(raw); if (metadata.fields().size() > 64) { return null; }
            ValuePlan value = new ValuePlan(type, 18); value.constructor = metadata.constructor(); value.options = CodecOptions.forClass(raw, engine.options);
            value.prefix = concat(id(engine.typeId(raw)), id("default-pojo"), intBytes(1));
            List<FieldPlan> fields = new ArrayList<>();
            for (FieldMetadata entry : metadata.fields()) {
                Field field = entry.field(); PojoCodec annotation = field.getDeclaredAnnotation(PojoCodec.class);
                if (annotation != null && annotation.fieldCodec() != DefaultFieldCodec.class) { return null; }
                ValuePlan member = compile(GenericTypeResolver.resolve(field.getGenericType(), type), active); if (member == null) { return null; }
                byte[] name = id(field.getName());
                byte[] header = concat(name, new byte[]{2}, id("default-field"), intBytes(1));
                int scalarWidth = width(member.kind);
                byte[] inline = member.kind <= 9 ? concat(header, intBytes(scalarWidth < 0 ? 0 : scalarWidth + 5), new byte[]{(byte)member.kind}, intBytes(Math.max(0, scalarWidth))) : null;
                fields.add(new FieldPlan(field, member, value.options.overlay(annotation), name, header,
                        concat(name, new byte[]{1}, intBytes(0), intBytes(0), intBytes(0)), inline));
            }
            value.fields = fields.toArray(FieldPlan[]::new); return value;
        } finally { active.remove(raw); }
    }
    private byte[] id(String text) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try { FrameIO.writeString(encoded, text); } catch (IOException invalidUtf16) { throw UNSUPPORTED; }
        if (encoded.size() - 4 > engine.limits.identifierBytes()) { throw UNSUPPORTED; }
        return encoded.toByteArray();
    }
    private static byte[] intBytes(int value) { return new byte[]{(byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value}; }
    private static byte[] concat(byte[]... values) {
        int size = 0; for (byte[] value : values) { size += value.length; }
        byte[] result = new byte[size]; int offset = 0;
        for (byte[] value : values) { System.arraycopy(value, 0, result, offset, value.length); offset += value.length; }
        return result;
    }
    private Scratch borrow() throws IOException {
        Scratch cached = scratch.get();
        if (cached == null) { cached = new Scratch(engine.limits); scratch.set(cached); }
        Scratch result = cached.busy ? new Scratch(engine.limits) : cached;
        result.busy = true; result.session.reset(); result.buffer.size = 0; result.buffer.position = 0;
        result.session.reserve(result.buffer.bytes.length); return result;
    }
    private static final class Scratch {
        final Session session;
        final Buffer buffer;
        boolean busy;
        Scratch(CodecLimits limits) { session = new Session(limits); buffer = new Buffer(session); }
        void release() {
            session.release(buffer.bytes.length);
            if (buffer.bytes.length > RETAINED_BYTES) { buffer.bytes = new byte[0]; }
            busy = false;
        }
    }
    public boolean write(OutputStream stream, Class<?> type, Object bean) throws IOException {
        ValuePlan plan = plan(type); if (plan == null) { return false; }
        if (bean != null && !type.isInstance(bean)) { throw new PojoCodecException("Root value is not assignable to " + type.getName()); }
        Scratch state = borrow(); Buffer buffer = state.buffer;
        try {
            buffer.putInt(0x504a4344); buffer.putInt(0x00010000); buffer.putInt(0);
            writeValue(plan, bean, null, null, buffer, true);
            buffer.patch(8, buffer.size - 12); stream.write(buffer.bytes, 0, buffer.size); return true;
        } catch (UnsupportedPath unsupported) { return false; }
        catch (ReflectiveOperationException | RuntimeException exception) { throw state.session.error("Cannot encode " + type.getName(), exception); }
        finally { state.release(); }
    }
    private void writeValue(ValuePlan plan, Object value, Field field, Object bean, Buffer out, boolean root) throws IOException, IllegalAccessException {
        boolean primitive = field != null && field.getType().isPrimitive();
        out.session.enter(null);
        try {
            if (!primitive && value == null) { out.put(0); out.putInt(0); return; }
            int start = out.size; out.put(plan.kind); out.putInt(0);
            if (plan.kind <= 9) { writeScalar(plan.kind, value, primitive ? field : null, bean, out); }
            else if (plan.kind == 14) { out.putBytes((byte[]) value); }
            else if (plan.kind == 15) { writeArray(plan, value, out); }
            else if (plan.kind == 16) {
                if (value.getClass() != ArrayList.class) { throw UNSUPPORTED; }
                List<?> items = (List<?>) value; int count = items.size(); out.session.items(count); out.putBytes(plan.prefix); out.putInt(count);
                for (int index = 0; index < count; index++) { writeValue(plan.element, items.get(index), null, null, out, false); }
            } else {
                if (!root && value.getClass() != plan.raw) { throw UNSUPPORTED; }
                out.putBytes(plan.prefix); int body = out.size; out.putInt(0); int countPosition = out.size; out.putInt(0); int count = 0; boolean scalarDepthChecked = false;
                for (FieldPlan member : plan.fields) {
                    Field target = member.field(); Object item = target.getType().isPrimitive() ? null : target.get(value);
                    if (!target.getType().isPrimitive() && item == null) {
                        if (member.options().nullPolicy() == NullPolicy.OMIT) { continue; }
                        out.putBytes(member.nullHeader()); count++; continue;
                    }
                    if (item instanceof Collection<?> collection && collection.isEmpty() && member.options().emptyPolicy() == EmptyPolicy.OMIT) { continue; }
                    if (member.inlineHeader() != null) {
                        if (!scalarDepthChecked) { out.session.enter(null); out.session.leave(null); scalarDepthChecked = true; }
                        int fieldStart = out.size; out.putBytes(member.inlineHeader()); int payloadStart = out.size;
                        writeScalar(member.value().kind, item, target.getType().isPrimitive() ? target : null, value, out);
                        if (member.value().kind == 9) { int length = out.size - payloadStart; out.patch(fieldStart + member.valueHeader().length, length + 5); out.patch(payloadStart - 4, length); }
                        count++; continue;
                    }
                    out.putBytes(member.valueHeader()); int fieldBody = out.size; out.putInt(0);
                    try { writeValue(member.value(), item, target, value, out, false); }
                    catch (IOException exception) { throw out.session.error("field ." + target.getName(), exception); }
                    out.finishBody(fieldBody); count++;
                }
                if (count > engine.limits.fields()) { throw out.session.error("Too many fields"); }
                out.session.items(count); out.patch(countPosition, count); out.finishBody(body);
            }
            out.finishFrame(start);
        } finally { out.session.leave(null); }
    }
    private void writeScalar(int kind, Object value, Field field, Object bean, Buffer out) throws IOException, IllegalAccessException {
        switch (kind) {
            case 1 -> out.put((field == null ? (Boolean)value : field.getBoolean(bean)) ? 1 : 0);
            case 2 -> out.put(field == null ? (Byte)value : field.getByte(bean));
            case 3 -> out.putShort(field == null ? (Short)value : field.getShort(bean));
            case 4 -> out.putInt(field == null ? (Integer)value : field.getInt(bean));
            case 5 -> out.putLong(field == null ? (Long)value : field.getLong(bean));
            case 6 -> out.putInt(Float.floatToRawIntBits(field == null ? (Float)value : field.getFloat(bean)));
            case 7 -> out.putLong(Double.doubleToRawLongBits(field == null ? (Double)value : field.getDouble(bean)));
            case 8 -> out.putShort(field == null ? (Character)value : field.getChar(bean));
            case 9 -> out.putUtf8((String)value);
            default -> throw UNSUPPORTED;
        }
    }
    private void writeArray(ValuePlan plan, Object value, Buffer out) throws IOException {
        out.putBytes(plan.prefix); int count = Array.getLength(value); out.session.items(count); out.putInt(count);
        if (count > 0) { out.session.enter(null); out.session.leave(null); }
        int kind = plan.element.kind, width = width(kind), stride = width + 5;
        long required = (long)count * stride;
        if (required > Integer.MAX_VALUE) { throw out.session.error("Array exceeds frame size"); }
        out.ensure((int)required); byte[] target = out.bytes; int at = out.size;
        for (int index = 0; index < count; index++) {
            target[at] = (byte)kind; target[at+1] = 0; target[at+2] = 0; target[at+3] = 0; target[at+4] = (byte)width;
            int position = at + 5;
            switch (kind) {
                case 1 -> target[position] = (byte)(((boolean[])value)[index] ? 1 : 0);
                case 2 -> target[position] = ((byte[])value)[index];
                case 3 -> rawShort(target, position, ((short[])value)[index]);
                case 4 -> rawInt(target, position, ((int[])value)[index]);
                case 5 -> rawLong(target, position, ((long[])value)[index]);
                case 6 -> rawInt(target, position, Float.floatToRawIntBits(((float[])value)[index]));
                case 7 -> rawLong(target, position, Double.doubleToRawLongBits(((double[])value)[index]));
                case 8 -> rawShort(target, position, ((char[])value)[index]);
                default -> throw UNSUPPORTED;
            }
            at += stride;
        }
        out.size = at;
    }
    private static void rawShort(byte[] bytes, int at, int value) { bytes[at] = (byte)(value >>> 8); bytes[at+1] = (byte)value; }
    private static void rawInt(byte[] bytes, int at, int value) { bytes[at] = (byte)(value >>> 24); bytes[at+1] = (byte)(value >>> 16); bytes[at+2] = (byte)(value >>> 8); bytes[at+3] = (byte)value; }
    private static void rawLong(byte[] bytes, int at, long value) { rawInt(bytes, at, (int)(value >>> 32)); rawInt(bytes, at + 4, (int)value); }
    private static int width(int kind) { return switch (kind) { case 1, 2 -> 1; case 3, 8 -> 2; case 4, 6 -> 4; case 5, 7 -> 8; default -> -1; }; }
    public Object read(InputStream stream, Class<?> type) throws IOException {
        ValuePlan plan = plan(type); Scratch state = borrow(); Buffer buffer = state.buffer;
        try {
            buffer.ensure(12); readInto(stream, buffer.bytes, 0, 12); buffer.size = 12; buffer.position = 0; buffer.limit = 12;
            if (buffer.getInt() != 0x504a4344 || buffer.getInt() != 0x00010000) { throw state.session.error("Invalid document header/version/flags"); }
            int length = buffer.getInt();
            if (length < 5 || length > engine.limits.documentBytes() - 12 || length - 5 > engine.limits.frameBytes()) { throw state.session.error("Invalid root length"); }
            buffer.ensure(length); readInto(stream, buffer.bytes, 12, length); buffer.size += length; buffer.limit = buffer.size;
            Object value = readValue(plan, buffer, true); buffer.exact(buffer.limit); return value;
        } catch (ReflectiveOperationException | RuntimeException exception) { throw state.session.error("Cannot decode " + type.getName(), exception); }
        finally { state.release(); }
    }
    private static void readInto(InputStream stream, byte[] bytes, int offset, int size) throws IOException {
        int end = offset + size;
        while (offset < end) {
            int count = stream.read(bytes, offset, end - offset);
            if (count < 0) { throw new PojoCodecException("Truncated document"); }
            if (count == 0) { int next = stream.read(); if (next < 0) { throw new PojoCodecException("Truncated document"); } bytes[offset++] = (byte)next; }
            else { offset += count; }
        }
    }
    private Object readValue(ValuePlan plan, Buffer in, boolean root) throws IOException, ReflectiveOperationException {
        int start = in.position, kind = in.get(), length = in.length(engine.limits.frameBytes()), end = in.end(length);
        if (kind != 0 && (kind != plan.kind || (plan.prefix != null && !in.matchesAt(in.position, plan.prefix)))) {
            // Schema evolution, immutable containers, polymorphism and custom wire data use the full engine.
            in.position = end;
            return engine.readValue(new ByteArrayInputStream(in.bytes, start, end - start), plan.type,
                    new DecodeContext(engine, in.session, plan.type, engine.options, 1), root);
        }
        int parent = in.limit; in.limit = end; in.session.enter(null);
        try {
            Object value;
            if (kind == 0) { if (length != 0 || plan.raw.isPrimitive()) { throw in.session.error("Invalid NULL"); } value = null; }
            else if (kind <= 9) { value = readScalar(kind, in, length); }
            else if (kind == 14) { value = Arrays.copyOfRange(in.bytes, in.position, end); in.position = end; }
            else if (kind == 15) { in.position += plan.prefix.length; value = readArray(plan, in); }
            else if (kind == 16) {
                in.position += plan.prefix.length; int count = in.length(engine.limits.containerItems()); in.session.items(count);
                ArrayList<Object> list = new ArrayList<>(count);
                for (int index = 0; index < count; index++) { list.add(readValue(plan.element, in, false)); }
                value = list;
            } else {
                in.position += plan.prefix.length; int bodySize = in.length(engine.limits.frameBytes()), bodyEnd = in.end(bodySize);
                int payloadEnd = in.limit; in.limit = bodyEnd;
                int count = in.length(engine.limits.fields()); in.session.items(count);
                Object bean = plan.constructor.newInstance(NO_ARGS); long seen = 0; int next = 0; Set<String> unknown = null;
                for (int index = 0; index < count; index++) {
                    int fieldStart = in.position, found = -1;
                    if (next < plan.fields.length && in.matchesAt(fieldStart, plan.fields[next].name())) { found = next; }
                    else { for (int candidate = 0; candidate < plan.fields.length; candidate++) { if (in.matchesAt(fieldStart, plan.fields[candidate].name())) { found = candidate; break; } } }
                    if (found < 0) {
                        String name = in.readId(); if (unknown == null) { unknown = new HashSet<>(); }
                        if (!unknown.add(name)) { throw in.session.error("Duplicate field: " + name); }
                        skipField(in); continue;
                    }
                    if ((seen & (1L << found)) != 0) { throw in.session.error("Duplicate field: " + plan.fields[found].field().getName()); }
                    seen |= 1L << found; next = found + 1;
                    FieldPlan member = plan.fields[found];
                    try { readField(member, bean, in); }
                    catch (IOException exception) { throw in.session.error("field ." + member.field().getName(), exception); }
                }
                in.exact(bodyEnd); in.limit = payloadEnd; value = bean;
            }
            in.exact(end); return value;
        } finally { in.limit = parent; in.session.leave(null); }
    }
    private void skipField(Buffer in) throws IOException {
        int state = in.get(); String codecId = in.readId(); int version = in.getInt(), size = in.length(engine.limits.frameBytes());
        if (state < 1 || state > 2 || (state == 1 && (!codecId.isEmpty() || version != 0 || size != 0))) { throw in.session.error("Invalid unknown field record"); }
        in.position = in.end(size);
    }
    private void readField(FieldPlan member, Object bean, Buffer in) throws IOException, ReflectiveOperationException {
        int start = in.position;
        if (member.inlineHeader() != null && member.value().kind <= 8 && in.matchesAt(start, member.inlineHeader())) {
            in.session.enter(null);
            try {
                in.position = start + member.inlineHeader().length;
                if (member.field().getType().isPrimitive()) { setPrimitive(member.field(), bean, member.value().kind, in); }
                else { member.field().set(bean, readScalar(member.value().kind, in, width(member.value().kind))); }
                return;
            } finally { in.session.leave(null); }
        }
        in.position += member.name().length; int state = in.get();
        if (state == 1) {
            if (in.getInt() != 0 || in.getInt() != 0 || in.getInt() != 0 || member.field().getType().isPrimitive()) { throw in.session.error("Invalid NULL field"); }
            member.field().set(bean, null); return;
        }
        if (state != 2 || !in.matchesAt(start, member.valueHeader())) { throw in.session.error("Invalid Field Codec/state"); }
        in.position = start + member.valueHeader().length;
        int end = in.end(in.length(engine.limits.frameBytes())), parent = in.limit; in.limit = end;
        try {
            ValuePlan value = member.value(); Field field = member.field();
            if (field.getType().isPrimitive() && in.matchesScalar(value.kind)) {
                in.get(); int size = in.getInt();
                if (size != width(value.kind)) { throw in.session.error("Invalid primitive length"); }
                in.session.enter(null);
                try { setPrimitive(field, bean, value.kind, in); } finally { in.session.leave(null); }
            } else { field.set(bean, readValue(value, in, false)); }
            in.exact(end);
        } finally { in.limit = parent; }
    }
    private Object readArray(ValuePlan plan, Buffer in) throws IOException {
        int count = in.length(engine.limits.containerItems()); in.session.items(count); Object result = Array.newInstance(plan.raw.getComponentType(), count);
        if (count > 0) { in.session.enter(null); in.session.leave(null); }
        int kind = plan.element.kind, width = width(kind);
        for (int index = 0; index < count; index++) {
            if (in.get() != kind || in.getInt() != width) { throw in.session.error("Array element type/length mismatch"); }
            switch (kind) {
                case 1 -> ((boolean[])result)[index] = in.booleanValue(); case 2 -> ((byte[])result)[index] = (byte)in.get();
                case 3 -> ((short[])result)[index] = (short)in.getShort(); case 4 -> ((int[])result)[index] = in.getInt();
                case 5 -> ((long[])result)[index] = in.getLong(); case 6 -> ((float[])result)[index] = Float.intBitsToFloat(in.getInt());
                case 7 -> ((double[])result)[index] = Double.longBitsToDouble(in.getLong()); case 8 -> ((char[])result)[index] = (char)in.getShort();
                default -> throw in.session.error("Unsupported primitive array");
            }
        }
        return result;
    }
    private static Object readScalar(int kind, Buffer in, int size) throws IOException {
        if (kind != 9 && size != width(kind)) { throw in.session.error("Invalid scalar length"); }
        return switch (kind) {
            case 1 -> in.booleanValue(); case 2 -> (byte)in.get(); case 3 -> (short)in.getShort(); case 4 -> in.getInt(); case 5 -> in.getLong();
            case 6 -> Float.intBitsToFloat(in.getInt()); case 7 -> Double.longBitsToDouble(in.getLong()); case 8 -> (char)in.getShort();
            case 9 -> in.readUtf8(size); default -> throw in.session.error("Invalid scalar kind");
        };
    }
    private static void setPrimitive(Field field, Object bean, int kind, Buffer in) throws IOException, IllegalAccessException {
        switch (kind) {
            case 1 -> field.setBoolean(bean, in.booleanValue()); case 2 -> field.setByte(bean, (byte)in.get());
            case 3 -> field.setShort(bean, (short)in.getShort()); case 4 -> field.setInt(bean, in.getInt()); case 5 -> field.setLong(bean, in.getLong());
            case 6 -> field.setFloat(bean, Float.intBitsToFloat(in.getInt())); case 7 -> field.setDouble(bean, Double.longBitsToDouble(in.getLong())); case 8 -> field.setChar(bean, (char)in.getShort());
            default -> throw in.session.error("Invalid primitive kind");
        }
    }
    private static final class Buffer {
        final Session session; byte[] bytes = new byte[0]; int size; int position; int limit;
        final CodecLimits limits;
        final int maximum;
        Buffer(Session session) { this.session = session; this.limits = session.limits; this.maximum = (int)Math.min(limits.documentBytes(), (long)limits.frameBytes() + 17); }
        void ensure(int additional) throws IOException {
            if (additional < 0 || additional > maximum - size) { throw session.error("Document/frame size exceeded"); }
            int required = size + additional;
            if (required > bytes.length) {
                int capacity = (int)Math.min(maximum, Math.max(required, Math.max(512L, (long)bytes.length * 2)));
                capacity = (int)Math.max(required, Math.min(capacity, session.limits.bufferBytes()));
                session.reserve(capacity);
                try { byte[] replacement = Arrays.copyOf(bytes, capacity); session.release(bytes.length); bytes = replacement; }
                catch (Throwable failure) { session.release(capacity); throw failure; }
            }
        }
        void put(int value) throws IOException { ensure(1); bytes[size++] = (byte)value; }
        void putShort(int value) throws IOException { ensure(2); bytes[size++] = (byte)(value >>> 8); bytes[size++] = (byte)value; }
        void putInt(int value) throws IOException { ensure(4); patch(size, value); size += 4; }
        void putLong(long value) throws IOException { putInt((int)(value >>> 32)); putInt((int)value); }
        void patch(int at, int value) { bytes[at] = (byte)(value >>> 24); bytes[at + 1] = (byte)(value >>> 16); bytes[at + 2] = (byte)(value >>> 8); bytes[at + 3] = (byte)value; }
        void putBytes(byte[] value) throws IOException { ensure(value.length); System.arraycopy(value, 0, bytes, size, value.length); size += value.length; }
        void finishBody(int at) throws IOException { int length = size - at - 4; if (length > limits.frameBytes()) { throw session.error("Body too large"); } patch(at, length); }
        void finishFrame(int at) throws IOException { int length = size - at - 5; if (length > limits.frameBytes()) { throw session.error("Frame too large"); } patch(at + 1, length); }
        void putUtf8(String text) throws IOException {
            if (text.length() > limits.stringBytes()) { throw session.error("String too long"); }
            int start = size; ensure(text.length());
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                if (ch < 0x80) { bytes[size++] = (byte)ch; }
                else if (ch < 0x800) { ensure(text.length() - index + 1); bytes[size++] = (byte)(0xc0 | ch >>> 6); bytes[size++] = (byte)(0x80 | ch & 0x3f); }
                else if (Character.isSurrogate(ch)) {
                    if (!Character.isHighSurrogate(ch) || index + 1 == text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) { throw session.error("Invalid UTF-16"); }
                    int point = Character.toCodePoint(ch, text.charAt(++index));
                    ensure(text.length() - index + 3);
                    bytes[size++] = (byte)(0xf0 | point >>> 18); bytes[size++] = (byte)(0x80 | point >>> 12 & 0x3f); bytes[size++] = (byte)(0x80 | point >>> 6 & 0x3f); bytes[size++] = (byte)(0x80 | point & 0x3f);
                } else { ensure(text.length() - index + 2); bytes[size++] = (byte)(0xe0 | ch >>> 12); bytes[size++] = (byte)(0x80 | ch >>> 6 & 0x3f); bytes[size++] = (byte)(0x80 | ch & 0x3f); }
                if (size - start > limits.stringBytes()) { throw session.error("String too long"); }
            }
        }
        void require(int count) throws IOException { if (count < 0 || position > limit - count) { throw session.error("Truncated frame"); } }
        int get() throws IOException { require(1); return bytes[position++] & 255; }
        int getShort() throws IOException { require(2); int value = (bytes[position] & 255) << 8 | bytes[position + 1] & 255; position += 2; return value; }
        int getInt() throws IOException { require(4); int value = bytes[position] << 24 | (bytes[position+1]&255)<<16 | (bytes[position+2]&255)<<8 | bytes[position+3]&255; position += 4; return value; }
        long getLong() throws IOException { return (long)getInt() << 32 | getInt() & 0xffffffffL; }
        boolean booleanValue() throws IOException { int value = get(); if (value > 1) { throw session.error("Invalid boolean"); } return value == 1; }
        int length(int maximum) throws IOException { int value = getInt(); if (value < 0 || value > maximum) { throw session.error("Invalid length: " + value); } return value; }
        int end(int length) throws IOException { require(length); return position + length; }
        void exact(int end) throws IOException { if (position != end) { throw session.error("Unconsumed frame bytes"); } }
        boolean matchesAt(int at, byte[] expected) { return at >= 0 && expected.length <= limit - at && Arrays.equals(bytes, at, at + expected.length, expected, 0, expected.length); }
        boolean matchesScalar(int kind) { return position < limit && (bytes[position]&255) == kind; }
        String readId() throws IOException { return readUtf8(length(limits.identifierBytes()), limits.identifierBytes()); }
        String readUtf8(int length) throws IOException {
            return readUtf8(length, limits.stringBytes());
        }
        String readUtf8(int length, int maximum) throws IOException {
            require(length); if (length > maximum) { throw session.error("String too long"); }
            int end = position + length;
            for (int index = position; index < end;) {
                int first = bytes[index++] & 255; if (first < 128) { continue; }
                int extra, point;
                if (first >= 0xc2 && first <= 0xdf) { extra = 1; point = first & 31; }
                else if (first >= 0xe0 && first <= 0xef) { extra = 2; point = first & 15; }
                else if (first >= 0xf0 && first <= 0xf4) { extra = 3; point = first & 7; }
                else { throw session.error("Invalid UTF-8"); }
                if (extra > end - index) { throw session.error("Invalid UTF-8"); }
                for (int n = 0; n < extra; n++) { int part = bytes[index++] & 255; if ((part & 0xc0) != 0x80) { throw session.error("Invalid UTF-8"); } point = point << 6 | part & 63; }
                if ((extra == 2 && point < 0x800) || (extra == 3 && point < 0x10000) || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) { throw session.error("Invalid UTF-8"); }
            }
            String result = new String(bytes, position, length, StandardCharsets.UTF_8); position = end; return result;
        }
    }
}
