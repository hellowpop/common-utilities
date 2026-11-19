package com.inzent.apim.utilities.pojocodec.metadata;

import java.lang.reflect.*;
import java.util.*;

public final class GenericTypeResolver {
    private GenericTypeResolver() { }
    public static Class<?> raw(Type type) {
        if (type instanceof Class<?> concrete) { return concrete; }
        if (type instanceof ParameterizedType parameterized) { return raw(parameterized.getRawType()); }
        if (type instanceof GenericArrayType array) { return Array.newInstance(raw(array.getGenericComponentType()), 0).getClass(); }
        if (type instanceof WildcardType wildcard) { return raw(wildcard.getUpperBounds()[0]); }
        if (type instanceof TypeVariable<?> variable) { return raw(variable.getBounds()[0]); }
        throw new IllegalArgumentException("Unsupported Type: " + type);
    }
    public static Type argument(Type type, int index) {
        if (type instanceof ParameterizedType parameterized && index < parameterized.getActualTypeArguments().length) {
            return parameterized.getActualTypeArguments()[index];
        }
        return Object.class;
    }
    public static Type resolve(Type member, Class<?> owner) { return resolve(member, (Type) owner); }
    public static Type resolve(Type member, Type owner) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        collect(owner, bindings, new HashSet<>());
        return substitute(member, bindings, new HashSet<>());
    }
    private static void collect(Type owner, Map<TypeVariable<?>, Type> bindings, Set<Type> visited) {
        if (owner == null || !visited.add(owner)) { return; }
        Class<?> type = raw(owner);
        if (owner instanceof ParameterizedType parameterized) {
            TypeVariable<?>[] variables = type.getTypeParameters(); Type[] args = parameterized.getActualTypeArguments();
            for (int index = 0; index < variables.length; index++) { bindings.put(variables[index], substitute(args[index], bindings, new HashSet<>())); }
        }
        Type parent = type.getGenericSuperclass();
        if (parent != null) { collect(substitute(parent, bindings, new HashSet<>()), bindings, visited); }
        for (Type parentInterface : type.getGenericInterfaces()) { collect(substitute(parentInterface, bindings, new HashSet<>()), bindings, visited); }
    }
    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings, Set<TypeVariable<?>> resolving) {
        if (type instanceof TypeVariable<?> variable) {
            Type value = bindings.get(variable);
            if (value == null || value == type || !resolving.add(variable)) { return type; }
            Type result = substitute(value, bindings, resolving); resolving.remove(variable); return result;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            for (int index = 0; index < args.length; index++) { args[index] = substitute(args[index], bindings, resolving); }
            return new Parameterized(parameterized.getRawType(), parameterized.getOwnerType(), args);
        }
        if (type instanceof GenericArrayType array) {
            Type component = substitute(array.getGenericComponentType(), bindings, resolving);
            return component instanceof Class<?> concrete ? Array.newInstance(concrete, 0).getClass() : new GenericArray(component);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds(), lower = wildcard.getLowerBounds();
            for (int i = 0; i < upper.length; i++) { upper[i] = substitute(upper[i], bindings, resolving); }
            for (int i = 0; i < lower.length; i++) { lower[i] = substitute(lower[i], bindings, resolving); }
            return new Wildcard(upper, lower);
        }
        return type;
    }
    private record Parameterized(Type rawType, Type ownerType, Type[] arguments) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return arguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return ownerType; }
    }
    private record GenericArray(Type component) implements GenericArrayType {
        @Override public Type getGenericComponentType() { return component; }
    }
    private record Wildcard(Type[] upper, Type[] lower) implements WildcardType {
        @Override public Type[] getUpperBounds() { return upper.clone(); }
        @Override public Type[] getLowerBounds() { return lower.clone(); }
    }
}
