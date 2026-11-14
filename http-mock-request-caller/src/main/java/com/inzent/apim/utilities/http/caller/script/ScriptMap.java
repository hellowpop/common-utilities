package com.inzent.apim.utilities.http.caller.script;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** JSON-only mutable facade. Host maps are never exposed to the evaluator. */
public final class ScriptMap extends AbstractMap<String,Object> {
    private final Map<String,Object> values = new LinkedHashMap<>();
    public ScriptMap(Map<String,?> source) { weight(source, 0); source.forEach((k,v) -> values.put(k, safe(v, 0, new IdentityHashMap<>()))); check(); }
    private ScriptMap() { }
    @Override public Object get(Object key) { return values.get(key); }
    @Override public Object put(String key, Object value) {
        if (key == null || key.length() > 1024) throw new IllegalArgumentException("Invalid script key");
        weight(value, 1); Object safe = safe(value, 0, new IdentityHashMap<>());
        boolean existed = values.containsKey(key); Object old = values.put(key, safe);
        try { check(); } catch(RuntimeException e) { if(existed) values.put(key,old); else values.remove(key); throw e; }
        return old;
    }
    @Override public Set<Entry<String,Object>> entrySet() { return Collections.unmodifiableMap(values).entrySet(); }
    public void setHeader(String name, Object value) {
        Object headers = get("headers");
        if (!(headers instanceof ScriptMap map)) throw new IllegalArgumentException("Headers unavailable");
        map.put(name, value);
    }
    public void assertSuccess(boolean success, String reason) {
        if (!success) { put("success", false); put("reason", reason); }
    }
    static Object safe(Object value, int depth, IdentityHashMap<Object,Boolean> seen) {
        if (depth > 8) throw new IllegalArgumentException("Script values exceed depth 8");
        if(value == null || value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) return value;
        if(value instanceof String s) { if(s.length()>1048576 || s.getBytes(StandardCharsets.UTF_8).length>1048576) throw new IllegalArgumentException("Script text exceeds 1 MiB"); return s; }
        if(seen.put(value,Boolean.TRUE)!=null) throw new IllegalArgumentException("Cyclic script value");
        try {
            if(value instanceof Map<?,?> source) {
                if(source.size()>256) throw new IllegalArgumentException("Script map exceeds 256 entries");
                var result = new ScriptMap();
                source.forEach((k,v)-> { if(!(k instanceof String s) || s.length()>1024) throw new IllegalArgumentException("Script keys must be strings"); result.values.put(s,safe(v,depth+1,seen)); });
                return result;
            }
            if(value instanceof List<?> list) {
                if(list.size()>4096) throw new IllegalArgumentException("Script list too large");
                var result = new ArrayList<Object>(); for(Object v:list) result.add(safe(v,depth+1,seen)); return new ScriptList(result);
            }
            if(value instanceof Object[] array) return safe(Arrays.asList(array), depth, seen);
            throw new IllegalArgumentException("Unsupported script value type");
        } finally { seen.remove(value); }
    }
    void check() { if(weight(this,0)>1048576) throw new IllegalArgumentException("Script map exceeds 1 MiB"); }
    private static long weight(Object value,int depth) {
        if(depth>8) throw new IllegalArgumentException("Script values exceed depth 8");
        long size=0;
        if(value instanceof Map<?,?> map) {
            if(map.size()>256) throw new IllegalArgumentException("Script map exceeds 256 entries");
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("Script keys must be strings");
                size+=key.length()*3L+weight(entry.getValue(),depth+1);limit(size);
            }
        } else if(value instanceof List<?> list) {
            if(list.size()>4096)throw new IllegalArgumentException("Script list too large");
            for(Object child:list){size+=weight(child,depth+1);limit(size);}
        } else if(value instanceof Object[] array) {
            if(array.length>4096)throw new IllegalArgumentException("Script list too large");
            for(Object child:array){size+=weight(child,depth+1);limit(size);}
        } else size=value instanceof String text?text.getBytes(StandardCharsets.UTF_8).length:16;
        limit(size);return size;
    }
    private static void limit(long size) {if(size>1048576)throw new IllegalArgumentException("Script map exceeds 1 MiB");}
    @SuppressWarnings("unchecked")
    static void copyBack(Map<String,Object> target, ScriptMap source) {
        for(var e:source.values.entrySet()) {
            Object old=target.get(e.getKey()), value=e.getValue();
            if(value instanceof ScriptMap child) {
                Map<String,Object> nested = old instanceof Map<?,?> ? (Map<String,Object>)old : new LinkedHashMap<>();
                copyBack(nested,child); if(old != nested) target.put(e.getKey(),nested);
            } else { Object converted=plain(value); if(!target.containsKey(e.getKey()) || !Objects.equals(old,converted)) target.put(e.getKey(),converted); }
        }
    }
    private static Object plain(Object value) {
        if(value instanceof ScriptMap m) { var result=new LinkedHashMap<String,Object>();copyBack(result,m);return result; }
        if(value instanceof List<?> list) {var result=new ArrayList<Object>();for(Object v:list)result.add(plain(v));return result;}
        return value;
    }
}
