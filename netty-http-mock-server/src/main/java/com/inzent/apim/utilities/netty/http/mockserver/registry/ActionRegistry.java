package com.inzent.apim.utilities.netty.http.mockserver.registry;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.discovery.ClasspathScanner;
import com.inzent.apim.utilities.netty.http.mockserver.processor.AnnotationProcessor;
import com.inzent.apim.utilities.netty.http.mockserver.response.ActionResult;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import java.lang.reflect.Modifier;
import java.util.*;

/** Immutable header-value to processor bindings. Discovery/reflection is confined to startup. */
public final class ActionRegistry {
    private final Map<String,AnnotationProcessor> bindings;
    private ActionRegistry(Map<String,AnnotationProcessor> bindings) { this.bindings=Map.copyOf(bindings); }
    public static ActionRegistry discover(List<String> packages) { return ofClasses(ClasspathScanner.scan(packages)); }
    public static ActionRegistry ofClasses(Collection<Class<?>> classes) {
        Map<String,AnnotationProcessor> bindings=new LinkedHashMap<>();
        for (Class<?> type:classes.stream().distinct().sorted(Comparator.comparing(Class::getName)).toList()) {
            HttpResponseType responseType=type.getDeclaredAnnotation(HttpResponseType.class);
            if (responseType==null) continue;
            if (!validName(responseType.value())) throw new IllegalArgumentException("Invalid HttpResponseType: "+type.getName());
            if (!AnnotationProcessor.class.isAssignableFrom(type) || !Modifier.isPublic(type.getModifiers())
                    || Modifier.isAbstract(type.getModifiers()) || type.isInterface())
                throw new IllegalArgumentException("HttpResponseType requires a public concrete AnnotationProcessor: "+type.getName());
            if (bindings.containsKey(responseType.value())) throw new IllegalArgumentException("Duplicate response type: "+responseType.value());
            try {
                Object instance=type.getConstructor().newInstance();
                if (!(instance instanceof AnnotationProcessor processor)) throw new IllegalArgumentException("Not a processor");
                bindings.put(responseType.value(),processor);
            } catch (ReflectiveOperationException | LinkageError ex) {
                throw new IllegalArgumentException("Processor registration failed: "+type.getName(),ex);
            }
        }
        return new ActionRegistry(bindings);
    }
    public static boolean validName(String name) { return name!=null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"); }
    public Set<String> names() { return bindings.keySet(); }
    public ActionResult execute(String name,Channel channel,FullHttpRequest request) {
        AnnotationProcessor processor=bindings.get(name);
        if (processor==null) throw new IllegalArgumentException("Unknown action");
        return processor.process(channel,request);
    }
}
