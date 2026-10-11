package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PlanIOTest {
  static class Stalled extends InputStream {
    final CountDownLatch closed = new CountDownLatch(1);

    public int read() throws IOException {
      try {
        closed.await();
        throw new IOException();
      } catch (InterruptedException e) {
        throw new IOException();
      }
    }

    public void close() {
      closed.countDown();
    }
  }

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

  @Test
  void duplicateCallbackParametersAreRejected() {
    assertThrows(ChatGptException.class, () -> PlanLogin.parse("state=one&state=two&code=secret"));
  }
}
