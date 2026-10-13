package dev.mikoto2000.rei.core.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunCancellationCauseTest {
  @Test void cyclesTerminateAndWrappedInterruptionIsStillControlFlow() {
    var outer=new RuntimeException("private");var inner=new RuntimeException("private",outer);outer.initCause(inner);
    assertFalse(RunCancellation.isCancellation(outer));
    assertDoesNotThrow(()->RunCancellation.propagate(outer));
    var interrupted=new RuntimeException("private",new InterruptedException("private"));
    assertTrue(RunCancellation.isCancellation(interrupted));
    try {
      assertThrows(java.util.concurrent.CancellationException.class,()->RunCancellation.propagate(interrupted));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {Thread.interrupted();}
  }
}
