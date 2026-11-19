package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.codec.container.JdkContainers;
import com.inzent.apim.utilities.pojocodec.registry.*;
import com.inzent.apim.utilities.pojocodec.internal.Session;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BasicContainerCodecTest {
    static JdkContainers containers() { return new JdkContainers(new TypeRegistry(Map.of()), new ComparatorRegistry(Map.of()), new ContainerPolicyRegistry(Map.of())); }
    static Object copy(Object value) throws Exception {
        JdkContainers codecs = containers(); Session session = new Session(CodecLimits.defaults());
        JdkContainers.Snapshot snapshot = codecs.snapshot(value, Object.class, session);
        return codecs.restore(snapshot.header(), snapshot.items(), Object.class, session);
    }
    @Test void standardContainerKindsAndNullItemsArePreserved() throws Exception {
        List<Object> original = new LinkedList<>(Arrays.asList("a", null, 3));
        Object result = copy(original);
        Assertions.assertEquals(LinkedList.class, result.getClass()); Assertions.assertEquals(original, result);
        Assertions.assertEquals(Stack.class, copy(new Stack<>()).getClass());
        Assertions.assertEquals(IdentityHashMap.class, copy(new IdentityHashMap<>()).getClass());
        Assertions.assertEquals(WeakHashMap.class, copy(new WeakHashMap<>()).getClass());
    }
    @Test void propertiesIncludeInheritedStringLookup() throws Exception {
        Properties defaults = new Properties(); defaults.setProperty("parent", "v");
        Properties source = new Properties(defaults); source.put(9, 4); source.setProperty("child", "x");
        Properties result = (Properties) copy(source);
        Assertions.assertEquals("v", result.getProperty("parent")); Assertions.assertFalse(result.containsKey("parent"));
        Assertions.assertEquals(4, result.get(9));
    }
}
