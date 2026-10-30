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
}
