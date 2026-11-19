package com.inzent.apim.utilities.pojocodec.registry;

import com.inzent.apim.utilities.pojocodec.PojoCodecException;
import com.inzent.apim.utilities.pojocodec.codec.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class CodecRegistry {
    public record Registration(String id, int version, Object codec) {
        public Registration {
            Objects.requireNonNull(id); Objects.requireNonNull(codec);
            if (id.isEmpty() || version < 1 || !(codec instanceof AbstractTypeCodec<?> || codec instanceof AbstractFieldCodec)) { throw new IllegalArgumentException("Invalid Codec registration"); }
        }
    }
    private final Map<Class<?>, Registration> registered;
    private final ConcurrentHashMap<Class<?>, Registration> implicit = new ConcurrentHashMap<>();
    public CodecRegistry(List<Registration> registrations) {
        Map<Class<?>, Registration> classes = new HashMap<>(); Set<String> ids = new HashSet<>(Set.of("default-pojo", "default-field"));
        classes.put(DefaultTypeCodec.class, new Registration("default-pojo", 1, new DefaultTypeCodec<>()));
        classes.put(DefaultFieldCodec.class, new Registration("default-field", 1, new DefaultFieldCodec()));
        for (Registration registration : registrations) {
            boolean supported = registration.codec() instanceof AbstractTypeCodec<?> typeCodec
                    ? typeCodec.supportsVersion(registration.version()) : ((AbstractFieldCodec) registration.codec()).supportsVersion(registration.version());
            if (!supported) { throw new IllegalArgumentException("Codec cannot read its current version: " + registration.id()); }
            if (!ids.add(registration.id()) || classes.putIfAbsent(registration.codec().getClass(), registration) != null) { throw new IllegalArgumentException("Duplicate Codec ID/class"); }
        }
        registered = Map.copyOf(classes);
    }
    public Registration get(Class<?> codecClass) throws PojoCodecException {
        Registration registration = registered.get(codecClass);
        if (registration != null) { return registration; }
        registration = implicit.get(codecClass); if (registration != null) { return registration; }
        try {
            Registration created = new Registration(codecClass.getName(), 1, codecClass.getConstructor().newInstance());
            for (Registration known : registered.values()) { if (known.id().equals(created.id())) { throw new PojoCodecException("Implicit Codec ID collision"); } }
            Registration previous = implicit.putIfAbsent(codecClass, created); return previous == null ? created : previous;
        } catch (ReflectiveOperationException | RuntimeException exception) { throw new PojoCodecException("Codec needs public no-arg constructor or registered instance: " + codecClass.getName(), exception); }
    }
}
