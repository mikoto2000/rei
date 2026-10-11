package dev.mikoto2000.rei.mcp;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.mikoto2000.rei.core.chat.ProjectRunQueue;
import static org.junit.jupiter.api.Assertions.*;
class McpQueueTest {
  @Test void twoSlotsEightPendingAndCancellationReleaseWithoutChangingOtherRunLimits() {
    var work=new ArrayDeque<Runnable>();var queue=new ProjectRunQueue(work::add);var count=new AtomicInteger();
    for(int i=0;i<10;i++)queue.enqueue("p","mcp"+i,ProjectRunQueue.Access.READ_ONLY,count::incrementAndGet,()->{},()->{},e->{},true);
    assertEquals(2,work.size());
    assertThrows(ProjectRunQueue.CapacityExceededException.class,()->queue.enqueue("p","overflow",ProjectRunQueue.Access.READ_ONLY,()->{},()->{},()->{},e->{},true));
    queue.enqueue("other","ordinary",ProjectRunQueue.Access.READ_ONLY,()->{});assertEquals(3,work.size());
    assertTrue(queue.cancelQueued("mcp9"));
    queue.enqueue("p","replacement",ProjectRunQueue.Access.READ_ONLY,count::incrementAndGet,()->{},()->{},e->{},true);
    while(!work.isEmpty())work.remove().run();assertEquals(10,count.get());
    assertFalse(queue.containsRun("p","replacement"));
  }
}
