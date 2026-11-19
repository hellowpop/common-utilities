package com.inzent.apim.utilities.pojocodec.internal;

import com.inzent.apim.utilities.pojocodec.*;
import com.inzent.apim.utilities.pojocodec.annotation.*;
import com.inzent.apim.utilities.pojocodec.codec.*;
import com.inzent.apim.utilities.pojocodec.codec.container.JdkContainers;
import com.inzent.apim.utilities.pojocodec.codec.value.*;
import com.inzent.apim.utilities.pojocodec.metadata.*;
import com.inzent.apim.utilities.pojocodec.registry.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Common framing and dispatch; extension Codecs own only their bounded payloads. */
public final class Engine {
    public final CodecOptions options;
    public final CodecLimits limits;
    private final TypeRegistry types;
    private final CodecRegistry codecs;
    private final JdkContainers containers;
    public Engine(CodecOptions options, CodecLimits limits, TypeRegistry types, CodecRegistry codecs,
                  ComparatorRegistry comparators, ContainerPolicyRegistry policies) {
        this.options = options; this.limits = limits; this.types = types; this.codecs = codecs;
        this.containers = new JdkContainers(types, comparators, policies);
    }
    public void prepare(Type type) throws IOException { prepare(type, new HashSet<>()); }
    public String typeId(Class<?> type) throws IOException { return types.id(type); }
    public boolean defaultPojo(Class<?> type) throws IOException { return codecs.get(typeCodecClass(type)).codec().getClass() == DefaultTypeCodec.class; }
    private void prepare(Type type, Set<Class<?>> visited) throws IOException {
        Class<?> raw = GenericTypeResolver.raw(type);
        if (!visited.add(raw)) { return; }
        if (raw.isArray()) { types.declare(raw); prepare(raw.getComponentType(), visited); return; }
        if (type instanceof ParameterizedType parameterized) { for (Type arg : parameterized.getActualTypeArguments()) { prepare(arg, visited); } }
        if (raw == Object.class || raw == void.class) { return; }
        if (ScalarValueCodec.kind(raw) >= 0 || TemporalValueCodec.supports(raw)) { types.declare(raw); return; }
        if (Map.class.isAssignableFrom(raw) || Collection.class.isAssignableFrom(raw) || Map.Entry.class.isAssignableFrom(raw)) {
            types.declare(raw); return;
        }
        types.declare(raw);
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) { return; }
        Class<? extends AbstractTypeCodec> selected = typeCodecClass(raw);
        if (selected != DefaultTypeCodec.class) { codecs.get(selected); return; }
        ClassMetadata metadata = MetadataResolver.resolve(raw);
        for (FieldMetadata field : metadata.fields()) {
            validateFieldAnnotation(field.field());
            prepare(GenericTypeResolver.resolve(field.field().getGenericType(), type), visited);
        }
    }
    private Class<? extends AbstractTypeCodec> typeCodecClass(Class<?> type) throws IOException {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            PojoCodec annotation = current.getDeclaredAnnotation(PojoCodec.class);
            if (annotation != null) {
                if (annotation.fieldCodec() != DefaultFieldCodec.class) { throw new PojoCodecException("fieldCodec cannot be assigned to a class: " + current.getName()); }
                return annotation.typeCodec();
            }
        }
        return DefaultTypeCodec.class;
    }
    private void validateFieldAnnotation(Field field) throws IOException {
        PojoCodec annotation = field.getDeclaredAnnotation(PojoCodec.class);
        if (annotation != null && annotation.typeCodec() != DefaultTypeCodec.class) { throw new PojoCodecException("typeCodec cannot be assigned to a field: " + field); }
    }
    private CodecRegistry.Registration fieldCodec(Field field) throws IOException {
        validateFieldAnnotation(field); PojoCodec annotation = field.getDeclaredAnnotation(PojoCodec.class);
        return codecs.get(annotation == null ? DefaultFieldCodec.class : annotation.fieldCodec());
    }
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) { return type; }
        if (type == boolean.class) { return Boolean.class; } if (type == byte.class) { return Byte.class; }
        if (type == short.class) { return Short.class; } if (type == int.class) { return Integer.class; }
        if (type == long.class) { return Long.class; } if (type == float.class) { return Float.class; }
        if (type == double.class) { return Double.class; } if (type == char.class) { return Character.class; }
        return Void.class;
    }
    private Object assign(Object value, Type target, Session session) throws IOException {
        Class<?> raw = GenericTypeResolver.raw(target), box = boxed(raw);
        if (value == null) { if (raw.isPrimitive()) { throw session.error("NULL cannot be assigned to primitive " + raw.getName()); } return null; }
        if (box.isInstance(value)) { return value; }
        if (value instanceof Byte number) {
            if (box == Short.class) { return number.shortValue(); } if (box == Integer.class) { return number.intValue(); } if (box == Long.class) { return number.longValue(); }
        }
        if (value instanceof Short number) { if (box == Integer.class) { return number.intValue(); } if (box == Long.class) { return number.longValue(); } }
        if (value instanceof Integer number && box == Long.class) { return number.longValue(); }
        if (value instanceof Float number && box == Double.class) { return number.doubleValue(); }
        throw session.error("Expected " + target.getTypeName() + ", received " + value.getClass().getName());
    }
    public void writeValue(OutputStream stream, Object value, Type declared, EncodeContext context, boolean projection) throws IOException {
        Session session = context.session;
        if (value != null && !boxed(GenericTypeResolver.raw(declared)).isInstance(value)) { throw session.error("Value is not assignable to " + declared.getTypeName()); }
        session.enter(value);
        try (BudgetedBuffer payload = new BudgetedBuffer(session, limits.frameBytes())) {
            int kind;
            if (value == null) { if (GenericTypeResolver.raw(declared).isPrimitive()) { throw session.error("Primitive NULL"); } kind = 0; }
            else {
                Class<?> actual = value instanceof Enum<?> enumeration ? enumeration.getDeclaringClass() : value.getClass();
                kind = ScalarValueCodec.kind(actual);
                if (kind >= 0) { ScalarValueCodec.write(kind, payload, value, types, session); }
                else if (TemporalValueCodec.supports(actual)) { kind = 13; TemporalValueCodec.write(payload, value, context.options().zonePolicy() == ZonePolicy.INCLUDE); }
                else if (actual == byte[].class) { kind = 14; payload.write((byte[]) value); }
                else if (actual.isArray()) { kind = 15; writeArray(payload, value, declared, context); }
                else if (value instanceof Collection<?> || value instanceof Map<?, ?>) { kind = value instanceof Map<?, ?> ? 17 : 16; writeContainer(payload, value, declared, context); }
                else if (value instanceof Map.Entry<?, ?> entry) { kind = 19; writeEntry(payload, entry, declared, context); }
                else { kind = 18; writePojo(payload, value, declared, context, projection); }
            }
            DataOutputStream out = new DataOutputStream(stream); out.writeByte(kind); out.writeInt(payload.size()); payload.writeTo(out);
        } catch (PojoCodecException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw session.error("Cannot encode " + declared.getTypeName(), exception); }
        finally { session.leave(value); }
    }
    public Object readValue(InputStream stream, Type declared, DecodeContext context) throws IOException {
        return readValue(stream, declared, context, false);
    }
    public Object readValue(InputStream stream, Type declared, DecodeContext context, boolean exactRoot) throws IOException {
        Session session = context.session; session.enter(null);
        try {
            DataInputStream in = new DataInputStream(stream); int kind = in.readUnsignedByte();
            int size = FrameIO.length(in, limits.frameBytes()); BoundedInputStream body = new BoundedInputStream(in, size);
            Object value;
            if (kind == 0) { if (size != 0) { throw session.error("NULL has nonempty payload"); } value = null; }
            else if (kind >= 1 && kind <= 12) { value = ScalarValueCodec.read(kind, body, types, session); }
            else if (kind == 13) { value = TemporalValueCodec.read(body, limits.identifierBytes()); }
            else if (kind == 14) { value = FrameIO.readExact(body, size); }
            else if (kind == 15) { value = readArray(body, declared, context); }
            else if (kind == 16 || kind == 17) { value = readContainer(body, declared, context, kind == 17); }
            else if (kind == 18) { value = readPojo(body, declared, context, exactRoot); }
            else if (kind == 19) { value = readEntry(body, declared, context); }
            else { throw session.error("Unknown value kind: " + kind); }
            FrameIO.exhausted(body);
            return assign(value, declared, session);
        } catch (PojoCodecException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw session.error("Cannot decode " + declared.getTypeName(), exception); }
        finally { session.leave(null); }
    }
    private void writeArray(OutputStream stream, Object array, Type declared, EncodeContext context) throws IOException {
        DataOutputStream out = new DataOutputStream(stream); ContainerHeader.writeId(out, types.id(array.getClass()), context.session);
        int size = Array.getLength(array); context.session.items(size); out.writeInt(size);
        Type component = arrayComponent(declared, array.getClass().getComponentType());
        for (int i = 0; i < size; i++) {
            String old = context.session.push("[" + i + "]");
            try { context.writeValue(out, Array.get(array, i), component); } finally { context.session.restore(old); }
        }
    }
    private Object readArray(InputStream stream, Type declared, DecodeContext context) throws IOException {
        Class<?> arrayType = types.resolve(FrameIO.readString(stream, limits.identifierBytes()));
        if (!arrayType.isArray() || !GenericTypeResolver.raw(declared).isAssignableFrom(arrayType)) { throw context.session.error("Array type mismatch"); }
        int count = FrameIO.length(stream, limits.containerItems()); context.session.items(count);
        Object array = Array.newInstance(arrayType.getComponentType(), count); Type component = arrayComponent(declared, arrayType.getComponentType());
        for (int i = 0; i < count; i++) {
            String old = context.session.push("[" + i + "]");
            try { Array.set(array, i, context.readValue(stream, component)); } finally { context.session.restore(old); }
        }
        return array;
    }
    private static Type arrayComponent(Type declared, Class<?> fallback) {
        if (declared instanceof GenericArrayType array) { return array.getGenericComponentType(); }
        if (declared instanceof Class<?> type && type.isArray()) { return type.getComponentType(); }
        return fallback;
    }
    private void writeContainer(OutputStream stream, Object value, Type declared, EncodeContext context) throws IOException {
        JdkContainers.Snapshot snapshot = containers.snapshot(value, declared, context.session);
        snapshot.header().write(stream, context.session); DataOutputStream out = new DataOutputStream(stream); out.writeInt(snapshot.items().size());
        boolean map = value instanceof Map<?, ?>;
        for (int i = 0; i < snapshot.items().size(); i++) {
            String old = context.session.push("[" + i + "]");
            try {
                Object item = snapshot.items().get(i);
                if (map) { Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item; context.writeValue(out, entry.getKey(), GenericTypeResolver.argument(declared, 0)); context.writeValue(out, entry.getValue(), GenericTypeResolver.argument(declared, 1)); }
                else { context.writeValue(out, item, GenericTypeResolver.argument(declared, 0)); }
            } finally { context.session.restore(old); }
        }
    }
    private Object readContainer(InputStream stream, Type declared, DecodeContext context, boolean map) throws IOException {
        ContainerHeader header = ContainerHeader.read(stream, context.session);
        int count = FrameIO.length(stream, limits.containerItems()); context.session.items(count); List<Object> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String old = context.session.push("[" + i + "]");
            try {
                if (map) { Object key = context.readValue(stream, GenericTypeResolver.argument(declared, 0)); Object value = context.readValue(stream, GenericTypeResolver.argument(declared, 1)); items.add(new AbstractMap.SimpleImmutableEntry<>(key, value)); }
                else { items.add(context.readValue(stream, GenericTypeResolver.argument(declared, 0))); }
            } finally { context.session.restore(old); }
        }
        Object restored = containers.restore(header, items, declared, context.session);
        if ((restored instanceof Map<?, ?>) != map) { throw context.session.error("Map/Collection kind mismatch"); }
        return restored;
    }
    private void writeEntry(OutputStream stream, Map.Entry<?, ?> entry, Type declared, EncodeContext context) throws IOException {
        boolean immutable = entry instanceof AbstractMap.SimpleImmutableEntry<?, ?> || entry.getClass().getName().equals("java.util.KeyValueHolder")
                || entry.getClass().getName().contains("UnmodifiableEntry");
        stream.write(immutable ? 0 : 1);
        context.writeValue(stream, entry.getKey(), GenericTypeResolver.argument(declared, 0)); context.writeValue(stream, entry.getValue(), GenericTypeResolver.argument(declared, 1));
    }
    private Object readEntry(InputStream stream, Type declared, DecodeContext context) throws IOException {
        int mutable = new DataInputStream(stream).readUnsignedByte(); if (mutable > 1) { throw context.session.error("Invalid Entry flag"); }
        Object key = context.readValue(stream, GenericTypeResolver.argument(declared, 0)), value = context.readValue(stream, GenericTypeResolver.argument(declared, 1));
        return mutable == 1 ? new AbstractMap.SimpleEntry<>(key, value) : new AbstractMap.SimpleImmutableEntry<>(key, value);
    }
    @SuppressWarnings("unchecked")
    private void writePojo(OutputStream stream, Object bean, Type declared, EncodeContext context, boolean projection) throws IOException {
        Class<?> actual = projection ? GenericTypeResolver.raw(declared) : bean.getClass();
        if (actual == GenericTypeResolver.raw(declared)) { prepare(declared); }
        String typeId = types.id(actual); prepare(actual);
        CodecRegistry.Registration registration = codecs.get(typeCodecClass(actual));
        Type effective = actual == GenericTypeResolver.raw(declared) ? declared : actual;
        EncodeContext nested = context.with(effective, CodecOptions.forClass(actual, options), registration.version());
        ContainerHeader.writeId(stream, typeId, context.session); ContainerHeader.writeId(stream, registration.id(), context.session);
        DataOutputStream out = new DataOutputStream(stream); out.writeInt(registration.version());
        try (BudgetedBuffer body = new BudgetedBuffer(context.session, limits.frameBytes())) {
            ((AbstractTypeCodec<Object>) registration.codec()).writeBody(body, bean, nested);
            out.writeInt(body.size()); body.writeTo(out);
        }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object readPojo(InputStream stream, Type declared, DecodeContext context, boolean exactRoot) throws IOException {
        String typeId = FrameIO.readString(stream, limits.identifierBytes()); Class<?> actual = types.resolve(typeId);
        if (exactRoot && !typeId.equals(types.id(GenericTypeResolver.raw(declared)))) { throw context.session.error("Root type ID mismatch: " + typeId); }
        if (!GenericTypeResolver.raw(declared).isAssignableFrom(actual)) { throw context.session.error("POJO type mismatch: " + typeId); }
        prepare(actual); CodecRegistry.Registration registration = codecs.get(typeCodecClass(actual));
        String codecId = FrameIO.readString(stream, limits.identifierBytes()); int version = new DataInputStream(stream).readInt();
        AbstractTypeCodec codec = (AbstractTypeCodec) registration.codec();
        if (!registration.id().equals(codecId) || version < 1 || !codec.supportsVersion(version)) { throw context.session.error("Unsupported Type Codec ID/version: " + codecId + "/" + version); }
        BoundedInputStream body = new BoundedInputStream(stream, FrameIO.length(stream, limits.frameBytes()));
        Type effective = actual == GenericTypeResolver.raw(declared) ? declared : actual;
        Object value = codec.readBody(body, actual, context.with(effective, CodecOptions.forClass(actual, options), version));
        FrameIO.exhausted(body);
        if (value == null || !actual.isInstance(value)) { throw context.session.error("Type Codec returned wrong type/null"); }
        return value;
    }
    public void writePojoBody(OutputStream stream, Object bean, EncodeContext context) throws IOException {
        ClassMetadata metadata = MetadataResolver.resolve(GenericTypeResolver.raw(context.type())); List<Field> included = new ArrayList<>();
        for (FieldMetadata entry : metadata.fields()) {
            Field field = entry.field();
            if (!omit(bean, field, context.options().overlay(field.getDeclaredAnnotation(PojoCodec.class)), context.session)) { included.add(field); }
        }
        if (included.size() > limits.fields()) { throw context.session.error("Too many fields"); }
        context.session.items(included.size()); new DataOutputStream(stream).writeInt(included.size());
        for (Field field : included) { writeFieldRecord(stream, bean, field, context); }
    }
    public Object readPojoBody(InputStream stream, Class<?> type, DecodeContext context) throws IOException {
        ClassMetadata metadata = MetadataResolver.resolve(type); Object bean;
        try { bean = metadata.constructor().newInstance(); }
        catch (ReflectiveOperationException exception) { throw context.session.error("Cannot instantiate " + type.getName(), exception); }
        Map<String, Field> fields = new HashMap<>(); for (FieldMetadata entry : metadata.fields()) { fields.put(entry.field().getName(), entry.field()); }
        int count = FrameIO.length(stream, limits.fields()); context.session.items(count); Set<String> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String name = FrameIO.readString(stream, limits.identifierBytes());
            if (!seen.add(name)) { throw context.session.error("Duplicate field: " + name); }
            readFieldRecordBody(stream, bean, fields.get(name), context, false);
        }
        return bean;
    }
    private boolean omit(Object bean, Field field, CodecOptions effective, Session session) throws IOException {
        Object value = get(field, bean, session);
        return value == null ? effective.nullPolicy() == NullPolicy.OMIT : effective.emptyPolicy() == EmptyPolicy.OMIT
                && ((value instanceof Collection<?> collection && collection.isEmpty()) || (value instanceof Map<?, ?> map && map.isEmpty()));
    }
    private static Object get(Field field, Object bean, Session session) throws IOException {
        try { return field.get(bean); } catch (IllegalAccessException exception) { throw session.error("Field access " + field, exception); }
    }
    public void validateField(Object bean, Field field) throws IOException {
        if (bean == null || field == null || !field.getDeclaringClass().isInstance(bean)) { throw new PojoCodecException("Field does not belong to bean"); }
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic() || !field.trySetAccessible()) { throw new PojoCodecException("Unsupported field: " + field); }
        validateFieldAnnotation(field);
    }
    public void writeFieldRecord(OutputStream stream, Object bean, Field field, EncodeContext context) throws IOException {
        String old = context.session.push("." + field.getName());
        try {
            CodecOptions effective = context.options().overlay(field.getDeclaredAnnotation(PojoCodec.class));
            Type fieldType = GenericTypeResolver.resolve(field.getGenericType(), context.type());
            prepare(fieldType); Object value = get(field, bean, context.session);
            int state = omit(bean, field, effective, context.session) ? 0 : value == null ? 1 : 2;
            ContainerHeader.writeId(stream, field.getName(), context.session); DataOutputStream out = new DataOutputStream(stream); out.writeByte(state);
            if (state < 2) { FrameIO.writeString(out, ""); out.writeInt(0); out.writeInt(0); return; }
            CodecRegistry.Registration registration = fieldCodec(field);
            ContainerHeader.writeId(out, registration.id(), context.session); out.writeInt(registration.version());
            try (BudgetedBuffer body = new BudgetedBuffer(context.session, limits.frameBytes())) {
                ((AbstractFieldCodec) registration.codec()).writeField(body, bean, field, context.with(fieldType, effective, registration.version()));
                out.writeInt(body.size()); body.writeTo(out);
            }
        } finally { context.session.restore(old); }
    }
    public void readSingleField(InputStream stream, Object bean, Field field, DecodeContext context) throws IOException {
        String name = FrameIO.readString(stream, limits.identifierBytes());
        if (!name.equals(field.getName())) { throw context.session.error("Field name mismatch: " + name); }
        readFieldRecordBody(stream, bean, field, context, true);
    }
    private void readFieldRecordBody(InputStream stream, Object bean, Field field, DecodeContext context, boolean allowAbsent) throws IOException {
        DataInputStream in = new DataInputStream(stream); int state = in.readUnsignedByte();
        String codecId = FrameIO.readString(in, limits.identifierBytes()); int version = in.readInt(), size = FrameIO.length(in, limits.frameBytes());
        if (state > 2 || (state == 0 && !allowAbsent) || (state < 2 && (!codecId.isEmpty() || version != 0 || size != 0))) { throw context.session.error("Invalid field record state/metadata"); }
        if (field == null) { FrameIO.skipExact(in, size); return; }
        String old = context.session.push("." + field.getName());
        try {
            if (state == 0) { return; }
            if (state == 1) {
                if (field.getType().isPrimitive()) { throw context.session.error("NULL assigned to primitive"); }
                field.set(bean, null); return;
            }
            CodecRegistry.Registration registration = fieldCodec(field); AbstractFieldCodec codec = (AbstractFieldCodec) registration.codec();
            if (!registration.id().equals(codecId) || version < 1 || !codec.supportsVersion(version)) { throw context.session.error("Unsupported Field Codec ID/version: " + codecId + "/" + version); }
            Type fieldType = GenericTypeResolver.resolve(field.getGenericType(), context.type()); prepare(fieldType);
            BoundedInputStream body = new BoundedInputStream(in, size);
            codec.readField(body, bean, field, context.with(fieldType, context.options().overlay(field.getDeclaredAnnotation(PojoCodec.class)), version));
            FrameIO.exhausted(body);
        } catch (IllegalAccessException exception) { throw context.session.error("Field assignment " + field, exception); }
        finally { context.session.restore(old); }
    }
}
