package com.inzent.apim.utilities.pojocodec;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
class WrapperContainerCodecTest {
    @SuppressWarnings("unchecked")
    @Test void immutableAndFixedSizeRemainRestricted() throws Exception {
        List<Object> immutable = (List<Object>) BasicContainerCodecTest.copy(List.of("x"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> immutable.add("y"));
        List<Object> fixed = (List<Object>) BasicContainerCodecTest.copy(Arrays.asList("a", "b"));
        Assertions.assertEquals("a", fixed.set(0, "z"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> fixed.add("y"));
        SortedSet<?> sorted = (SortedSet<?>) BasicContainerCodecTest.copy(Collections.unmodifiableSortedSet(new TreeSet<>(List.of("b", "a"))));
        Assertions.assertEquals("a", sorted.first());
        Assertions.assertThrows(UnsupportedOperationException.class, sorted::clear);
    }
}
