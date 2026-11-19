package com.inzent.apim.utilities.pojocodec;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
class SortedContainerCodecTest {
    @Test void priorityAndSortedContainersKeepComparator() throws Exception {
        TreeSet<Integer> set = new TreeSet<>(Comparator.reverseOrder()); set.addAll(List.of(1, 3, 2));
        Assertions.assertEquals(List.of(3, 2, 1), new ArrayList<>((Set<?>) BasicContainerCodecTest.copy(set)));
        PriorityQueue<Integer> queue = new PriorityQueue<>(Comparator.reverseOrder()); queue.addAll(List.of(2, 4, 1));
        Assertions.assertEquals(4, ((Queue<?>) BasicContainerCodecTest.copy(queue)).poll());
    }
}
