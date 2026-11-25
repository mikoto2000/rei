package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ResponsesAcceptanceBudgetTest {
  @TempDir Path root;
  private Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
  private ResponsesAcceptanceBudget.Limits limits =
      new ResponsesAcceptanceBudget.Limits(3, 1000, 600, Duration.ofHours(3), 28000, 10, 30);

  private ResponsesAcceptanceBudget budget() {
    return new ResponsesAcceptanceBudget(
        root.resolve("budget.jsonl"), "approved-model", limits, clock);
  }

  @Test
  void onlyKnownUsageReleasesUnusedReservationAndRestartKeepsTheTotals() {
    var slot = budget().reserve(600, 500);
    budget().settle(slot, new ModelTokenUsage(40L, 20L, 60L, 0L, 10L));
    var next = budget().reserve(600, 500);
    assertThat(next.id()).isEqualTo(2);
    var snapshot = budget().snapshot();
    assertThat(snapshot.calls()).isEqualTo(2);
    assertThat(snapshot.completed()).isEqualTo(1);
    assertThat(snapshot.input()).isEqualTo(640);
    assertThat(snapshot.output()).isEqualTo(520);
    assertThat(snapshot.microUsd()).isEqualTo(22000);
  }

  @Test
  void unknownAndCrashedRequestsKeepTheirFullReservations() {
    var slot = budget().reserve(600, 400);
    budget().unknown(slot);
    assertThat(budget().snapshot().unknown()).isEqualTo(1);
    assertThat(budget().snapshot().input()).isEqualTo(600);
    assertThatThrownBy(() -> budget().reserve(401, 1)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> budget().settle(slot, new ModelTokenUsage(1L, 1L, 2L, 0L, 0L)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(budget().snapshot().calls()).isEqualTo(1);
  }

  @Test
  void unfinishedReservationsAreChargedAfterProcessRestartWithoutInventingUsage() {
    budget().reserve(600, 400);
    assertThat(budget().snapshot().completed()).isZero();
    assertThat(budget().snapshot().unknown()).isEqualTo(1);
    assertThatThrownBy(() -> budget().reserve(401, 1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void callsInputOutputAndMoneyHaveIndependentHardBounds() {
    for (int i = 0; i < 3; i++) {
      var slot = budget().reserve(1, 1);
      budget().settle(slot, new ModelTokenUsage(1L, 1L, 2L, 0L, 0L));
    }
    assertThatThrownBy(() -> budget().reserve(1, 1)).isInstanceOf(IllegalStateException.class);
    var other =
        new ResponsesAcceptanceBudget(root.resolve("other.jsonl"), "approved-model", limits, clock);
    assertThatThrownBy(() -> other.reserve(1001, 1)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> other.reserve(1, 601)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> other.reserve(1000, 601)).isInstanceOf(IllegalStateException.class);
    assertThat(other.reserve(1000, 600).id()).isEqualTo(1);
    var money =
        new ResponsesAcceptanceBudget(
            root.resolve("money.jsonl"),
            "approved-model",
            new ResponsesAcceptanceBudget.Limits(3, 1000, 600, Duration.ofHours(3), 27999, 10, 30),
            clock);
    assertThatThrownBy(() -> money.reserve(1000, 600)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void expiryClockRollbackAndChangedApprovalFailClosed() {
    budget().reserve(1, 1);
    clock = Clock.offset(clock, Duration.ofHours(3));
    assertThatThrownBy(() -> budget().reserve(1, 1)).isInstanceOf(IllegalStateException.class);
    clock = Clock.offset(clock, Duration.ofHours(-4));
    assertThatThrownBy(() -> budget().reserve(1, 1)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            new ResponsesAcceptanceBudget(
                    root.resolve("budget.jsonl"), "another-model", limits, Clock.systemUTC())
                .reserve(1, 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void settlingTwiceOrBeyondAReservationStopsFurtherRequests() {
    var slot = budget().reserve(600, 400);
    budget().settle(slot, new ModelTokenUsage(10L, 10L, 20L, 0L, 0L));
    assertThatThrownBy(() -> budget().settle(slot, new ModelTokenUsage(10L, 10L, 20L, 0L, 0L)))
        .isInstanceOf(IllegalStateException.class);
    var next = budget().reserve(600, 400);
    assertThatThrownBy(() -> budget().settle(next, new ModelTokenUsage(601L, 10L, 611L, 0L, 0L)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> budget().reserve(1, 1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void concurrentReservationsCannotOverspend() throws Exception {
    var budget = budget();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(() -> {
              try {
                budget.reserve(400, 200);
                return true;
              } catch (IllegalStateException denied) {
                return false;
              }
            }));
      int accepted = 0;
      for (var result : results) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(2);
      assertThat(budget.snapshot().input()).isEqualTo(800);
    }
  }

  @Test
  void truncatedJournalCannotResetAQuota() throws Exception {
    Files.writeString(root.resolve("budget.jsonl"), "{\"partial\":");
    assertThatThrownBy(() -> budget().reserve(1, 1)).isInstanceOf(IllegalStateException.class);
  }
}
