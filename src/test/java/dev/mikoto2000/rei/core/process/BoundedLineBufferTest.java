package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 上限内に収めた行一時保持領域の結果と状態の契約を検証するテスト。 */
class BoundedLineBufferTest {

  /** 外部プロセスの管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void keepsOnlyLastLinesWithinCapacity() {
    BoundedLineBuffer buffer = new BoundedLineBuffer(3);

    buffer.add("one");
    buffer.add("two");
    buffer.add("three");
    buffer.add("four");

    assertEquals(List.of("two", "three", "four"), buffer.tail(10));
  }

  /** 要求された末尾行一覧を返すことを確認する。 */
  @Test
  void returnsRequestedTailLines() {
    BoundedLineBuffer buffer = new BoundedLineBuffer(5);

    buffer.add("one");
    buffer.add("two");
    buffer.add("three");

    assertEquals(List.of("two", "three"), buffer.tail(2));
  }
}
