package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class BoundedLineBufferTest {

  @Test
  void keepsOnlyLastLinesWithinCapacity() {
    BoundedLineBuffer buffer = new BoundedLineBuffer(3);

    buffer.add("one");
    buffer.add("two");
    buffer.add("three");
    buffer.add("four");

    assertEquals(List.of("two", "three", "four"), buffer.tail(10));
  }

  @Test
  void returnsRequestedTailLines() {
    BoundedLineBuffer buffer = new BoundedLineBuffer(5);

    buffer.add("one");
    buffer.add("two");
    buffer.add("three");

    assertEquals(List.of("two", "three"), buffer.tail(2));
  }
}
