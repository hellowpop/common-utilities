package com.inzent.apim.utilities.pojocodec.registry;

import com.inzent.apim.utilities.pojocodec.PojoCodecException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Only explicit registrations and statically discovered declared types are resolvable. */
public final class TypeRegistry {
    private final Map<Class<?>, String> explicit;
    private final ConcurrentHashMap<String, Class<?>> types = new ConcurrentHashMap<>();
    public TypeRegistry(Map<String, Class<?>> registrations) {
        java.util.HashMap<Class<?>, String> ids = new java.util.HashMap<>();
        registrations.forEach((id, type) -> {
            if (id.isEmpty() || ids.putIfAbsent(type, id) != null) { throw new IllegalArgumentException("Duplicate/empty type ID"); }
            types.put(id, type);
        });
        explicit = Map.copyOf(ids);
        Class<?>[] primitives = {boolean.class, byte.class, short.class, int.class, long.class, float.class, double.class, char.class};
        for (Class<?> primitive : primitives) { types.put(primitive.getName(), primitive); }
        types.put(String.class.getName(), String.class); types.put(Object.class.getName(), Object.class);
    }
    public String declare(Class<?> type) throws PojoCodecException {
        String id = explicit.getOrDefault(type, type.getName());
        Class<?> previous = types.putIfAbsent(id, type);
        if (previous != null && previous != type) { throw new PojoCodecException("Type ID collision: " + id); }
        return id;
    }
    public String id(Class<?> type) throws PojoCodecException {
        String id = explicit.getOrDefault(type, type.getName());
        if (types.get(id) != type) { throw new PojoCodecException("Type must be registered: " + type.getName()); }
        return id;
    }
    public Class<?> resolve(String id) throws PojoCodecException {
        Class<?> type = types.get(id);
        if (type == null) { throw new PojoCodecException("Unregistered type: " + id); }
        return type;
    }
}
