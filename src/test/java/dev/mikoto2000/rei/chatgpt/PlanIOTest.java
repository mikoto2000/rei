package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** 計画IOの結果と状態の契約を検証するテスト。 */
class PlanIOTest {
  /** 検証条件を再現するためのStalled。 */
  static class Stalled extends InputStream {
    /** 検証に使う終了済み。 */
    final CountDownLatch closed = new CountDownLatch(1);

    /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
    public int read() throws IOException {
      try {
        closed.await();
        throw new IOException();
      } catch (InterruptedException e) {
        throw new IOException();
      }
    }

    /** 検証で利用した対象の終了を扱う。 */
    public void close() {
      closed.countDown();
    }
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void stalledBodyTimesOutAndCloses() {
    var input = new Stalled();
    var error =
        assertThrows(
            ChatGptException.class,
            () ->
                PlanIO.read(
                    input, Duration.ofMillis(50), () -> false, () -> PlanIO.bytes(input, 1024)));
    assertEquals(ChatGptException.Code.TIMEOUT, error.code());
    assertEquals(0, input.closed.getCount());
  }

  /** ChatGPTとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationClosesBody() {
    var input = new Stalled();
    var error =
        assertThrows(
            ChatGptException.class,
            () ->
                PlanIO.read(
                    input, Duration.ofSeconds(1), () -> true, () -> PlanIO.bytes(input, 1024)));
    assertEquals(ChatGptException.Code.CANCELLED, error.code());
    assertEquals(0, input.closed.getCount());
  }

  /** ChatGPTとの連携で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateCallbackParametersAreRejected() {
    assertThrows(ChatGptException.class, () -> PlanLogin.parse("state=one&state=two&code=secret"));
  }
}
