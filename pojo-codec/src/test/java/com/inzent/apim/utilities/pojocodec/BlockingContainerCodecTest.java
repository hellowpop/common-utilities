package com.inzent.apim.utilities.pojocodec;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
class BlockingContainerCodecTest {
    @Test void boundedCapacityAndNonExpiredDelayItemsAreRetained() throws Exception {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(7); queue.add("a");
        ArrayBlockingQueue<?> result = (ArrayBlockingQueue<?>) BasicContainerCodecTest.copy(queue);
        Assertions.assertEquals(6, result.remainingCapacity()); Assertions.assertEquals("a", result.poll());
        DelayQueue<Delayed> delayed = new DelayQueue<>();
        delayed.add(new Delayed() {
            public long getDelay(TimeUnit unit) { return unit.convert(1, TimeUnit.DAYS); }
            public int compareTo(Delayed other) { return 0; }
        });
        Assertions.assertEquals(1, ((DelayQueue<?>) BasicContainerCodecTest.copy(delayed)).size());
        Assertions.assertEquals(0, ((SynchronousQueue<?>) BasicContainerCodecTest.copy(new SynchronousQueue<>())).size());
        Assertions.assertEquals(LinkedBlockingQueue.class, BasicContainerCodecTest.copy(new LinkedBlockingQueue<>(4)).getClass());
        Assertions.assertEquals(LinkedBlockingDeque.class, BasicContainerCodecTest.copy(new LinkedBlockingDeque<>(4)).getClass());
        Assertions.assertEquals(PriorityBlockingQueue.class, BasicContainerCodecTest.copy(new PriorityBlockingQueue<>()).getClass());
    }
}
