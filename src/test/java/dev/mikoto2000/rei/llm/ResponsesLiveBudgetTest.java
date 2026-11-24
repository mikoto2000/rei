package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ResponsesLiveBudgetTest {
  @TempDir Path root;

  private Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);

  private ResponsesLiveBudget budget() {
    return new ResponsesLiveBudget(root.resolve("calls.ledger"), "approved-target", clock);
  }

  @Test
  void failedOrRestartedRequestsDoNotRefundOrResetReservations() throws Exception {
    assertThat(budget().reserve(500, 2000)).isEqualTo(1);
    assertThat(budget().reserve(500, 2000)).isEqualTo(2);
    for (int call = 3; call <= 10; call++)
      assertThat(budget().reserve(500, 2000)).isEqualTo(call);
    assertThatThrownBy(() -> budget().reserve(500, 2000)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void timeLimitSurvivesRestartAndClockRollbackFailsClosed() throws Exception {
    budget().reserve(500, 2000);
    clock = Clock.offset(clock, Duration.ofMinutes(15));
    assertThatThrownBy(() -> budget().reserve(500, 2000)).isInstanceOf(IllegalStateException.class);
    clock = Clock.offset(clock, Duration.ofMinutes(-16));
    assertThatThrownBy(() -> budget().reserve(500, 2000)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void oversizedInputOrOutputIsRejectedBeforeAReservationIsWritten() throws Exception {
    assertThatThrownBy(() -> budget().reserve(7953, 2000)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> budget().reserve(500, 2001)).isInstanceOf(IllegalStateException.class);
    assertThat(budget().reserve(500, 2000)).isEqualTo(1);
  }

  @Test
  void aChangedTargetCannotReuseTheRemainingBudget() throws Exception {
    budget().reserve(500, 2000);
    var changed = new ResponsesLiveBudget(root.resolve("calls.ledger"), "other-target", clock);
    assertThatThrownBy(() -> changed.reserve(500, 2000)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void partialOrCorruptJournalCannotBeTreatedAsAnEmptyBudget() throws Exception {
    Files.writeString(root.resolve("calls.ledger"), "partial");
    assertThatThrownBy(() -> budget().reserve(500, 2000)).isInstanceOf(IllegalStateException.class);
    Files.writeString(root.resolve("calls.ledger"), "0,9,approved-target\n");
    assertThatThrownBy(() -> budget().reserve(500, 2000)).isInstanceOf(IllegalStateException.class);
  }
}
