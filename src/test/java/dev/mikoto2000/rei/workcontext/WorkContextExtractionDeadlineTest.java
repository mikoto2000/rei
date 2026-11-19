package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkContextExtractionDeadlineTest {
  @Test
  void batchesShareRemainingTimeAndClosingRestoresThePreviousScope() {
    var time = new AtomicLong();
    var maximum = Duration.ofSeconds(240);
    try (var outer = WorkContextExtractionDeadline.open(Duration.ofSeconds(2), time::get)) {
      time.set(1_500_000_000L);
      assertEquals(Duration.ofMillis(500), WorkContextExtractionDeadline.remaining(maximum));
      try (var inner = WorkContextExtractionDeadline.open(Duration.ofMillis(100), time::get)) {
        assertEquals(Duration.ofMillis(100), WorkContextExtractionDeadline.remaining(maximum));
      }
      assertEquals(Duration.ofMillis(500), WorkContextExtractionDeadline.remaining(maximum));
      try (var inner = WorkContextExtractionDeadline.open(Duration.ofSeconds(10), time::get)) {
        assertEquals(Duration.ofMillis(500), WorkContextExtractionDeadline.remaining(maximum));
      }
      time.set(2_000_000_001L);
      var error =
          assertThrows(
              IllegalStateException.class, () -> WorkContextExtractionDeadline.remaining(maximum));
      assertEquals(
          "timeout", dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error).code());
    }
    assertEquals(maximum, WorkContextExtractionDeadline.remaining(maximum));
  }
}
