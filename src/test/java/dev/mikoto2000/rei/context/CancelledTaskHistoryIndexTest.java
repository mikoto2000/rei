package dev.mikoto2000.rei.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CancelledTaskHistoryIndexTest {
  @Test
  void indexedLookupPreservesLegacyUnknownTimesTiesAndUnsortedAttempts() {
    for (int seed = 0; seed < 10; seed++) {
      var random = new Random(seed);
      var attempts = new ArrayList<CancelledTaskHistory.Attempt>();
      for (int index = 0; index < 30; index++) {
        var time = index % 9 == 0 ? null : Instant.EPOCH.plusMillis(random.nextInt(10));
        var status = List.of("FAILED", "CANCELLED", "COMPLETED", "RUNNING").get(random.nextInt(4));
        attempts.add(new CancelledTaskHistory.Attempt("run-" + index, status, time));
      }
      var policy = CancelledTaskHistory.from(attempts);
      var indexed = policy.indexedTimestampFilter();
      assertThat(indexed.test(null)).isEqualTo(policy.hides((Instant) null));
      for (int time = -1; time <= 12; time++) {
        var instant = Instant.EPOCH.plusMillis(time);
        assertThat(indexed.test(instant))
            .as("seed=%s time=%s", seed, time)
            .isEqualTo(policy.hides(instant));
      }
    }
  }

  @Test
  void noCancellationKeepsUnknownAndKnownTimesVisible() {
    var policy =
        CancelledTaskHistory.from(
            List.of(new CancelledTaskHistory.Attempt("run", "COMPLETED", null)));
    var indexed = policy.indexedTimestampFilter();
    assertThat(indexed.test(null)).isFalse();
    assertThat(indexed.test(Instant.EPOCH)).isFalse();
  }

  @Test
  void standaloneFailedAttemptIsArchivedWithoutACancellation() {
    var policy =
        CancelledTaskHistory.from(
            List.of(
                new CancelledTaskHistory.Attempt("before", "COMPLETED", Instant.EPOCH),
                new CancelledTaskHistory.Attempt("failed", "FAILED", Instant.EPOCH.plusSeconds(2)),
                new CancelledTaskHistory.Attempt(
                    "after", "COMPLETED", Instant.EPOCH.plusSeconds(4)),
                new CancelledTaskHistory.Attempt(
                    "current", "RUNNING", Instant.EPOCH.plusSeconds(6))));
    assertThat(policy.runIds()).containsExactly("failed");
    assertThat(policy.generation()).isEqualTo("failed");
    assertThat(policy.laterRunIds()).containsExactlyInAnyOrder("after", "current");
    assertThat(policy.hides(Instant.EPOCH.plusSeconds(1))).isFalse();
    assertThat(policy.hides(Instant.EPOCH.plusSeconds(3))).isTrue();
    assertThat(policy.hides(Instant.EPOCH.plusSeconds(5))).isFalse();
    assertThat(policy.allowsState("before")).isFalse();
    assertThat(policy.allowsState("failed")).isFalse();
    assertThat(policy.allowsState(null)).isFalse();
    assertThat(policy.allowsState("after")).isTrue();
    assertThat(policy.allowsState("current")).isTrue();
  }

  @Test
  void lastArchivedAttemptControlsStateButEveryFailureStaysHidden() {
    var policy =
        CancelledTaskHistory.from(
            List.of(
                new CancelledTaskHistory.Attempt("first-failure", "FAILED", Instant.EPOCH),
                new CancelledTaskHistory.Attempt(
                    "success-between", "COMPLETED", Instant.EPOCH.plusSeconds(2)),
                new CancelledTaskHistory.Attempt(
                    "cancelled", "CANCELLED", Instant.EPOCH.plusSeconds(4)),
                new CancelledTaskHistory.Attempt(
                    "second-failure", "FAILED", Instant.EPOCH.plusSeconds(6)),
                new CancelledTaskHistory.Attempt(
                    "current", "RUNNING", Instant.EPOCH.plusSeconds(8))));
    assertThat(policy.runIds())
        .containsExactlyInAnyOrder("first-failure", "cancelled", "second-failure");
    assertThat(policy.generation()).isEqualTo("second-failure");
    assertThat(policy.laterRunIds()).containsExactly("current");
    assertThat(policy.hides("success-between")).isFalse();
    assertThat(policy.hides(Instant.EPOCH.plusSeconds(3))).isFalse();
    assertThat(policy.hides(Instant.EPOCH.plusSeconds(5))).isTrue();
    assertThat(policy.hides(Instant.EPOCH.plusSeconds(7))).isTrue();
    assertThat(policy.allowsState("success-between")).isFalse();
    assertThat(policy.allowsState("cancelled")).isFalse();
    assertThat(policy.allowsState("current")).isTrue();
  }

  @Test
  void cancelledGenerationStillExcludesEarlierNonAdjacentFailure() {
    var policy =
        CancelledTaskHistory.from(
            List.of(
                new CancelledTaskHistory.Attempt("failed", "FAILED", Instant.EPOCH),
                new CancelledTaskHistory.Attempt(
                    "completed", "COMPLETED", Instant.EPOCH.plusSeconds(2)),
                new CancelledTaskHistory.Attempt(
                    "cancelled", "CANCELLED", Instant.EPOCH.plusSeconds(4)),
                new CancelledTaskHistory.Attempt(
                    "current", "RUNNING", Instant.EPOCH.plusSeconds(6))));
    assertThat(policy.runIds()).containsExactlyInAnyOrder("failed", "cancelled");
    assertThat(policy.generation()).isEqualTo("cancelled");
    assertThat(policy.hides("completed")).isFalse();
    assertThat(CancelledTaskHistory.summaryKey("chat", policy.generation()))
        .isEqualTo("chat/after-archive-v2/cancelled")
        .isNotEqualTo("chat/after-cancel/cancelled");
    assertThat(CancelledTaskHistory.summaryKey("chat/log", policy.generation()))
        .isEqualTo("chat/log/after-archive-v2/cancelled");
    assertThat(CancelledTaskHistory.summaryKey("chat", "")).isEqualTo("chat");
  }
}
