package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CompressionRetryGuardTest {
  private final AtomicLong time = new AtomicLong();
  private final CompressionRetryGuard guard = new CompressionRetryGuard(time::get);

  @Test
  void suppressesRepeatedRequestsAndAnnouncesOnlyOnceUntilNextAttempt() {
    assertThat(guard.decision("run", "config").allowed()).isTrue();
    assertThat(guard.failed("run", "config", "summary_timeout", 40000, 3000, 120, true))
        .isEqualTo(120);
    assertThat(guard.decision("run", "config"))
        .isEqualTo(new CompressionRetryGuard.Decision(false, true, 120, "summary_timeout"));
    assertThat(guard.decision("run", "config").announce()).isFalse();
    time.set(119001);
    assertThat(guard.decision("run", "config").waitSeconds()).isEqualTo(1);
    time.set(120000);
    assertThat(guard.decision("run", "config").allowed()).isTrue();
    assertThat(guard.inputLimit("run", "config", 100000)).isEqualTo(20000);
  }

  @Test
  void exponentiallyBacksOffAndBoundsTheShrinkingInputAndWaitingTime() {
    for (long seconds : new long[] {120, 240, 480, 960, 960}) {
      long input = guard.inputLimit("run", "config", 40000);
      assertThat(guard.failed("run", "config", "summary_timeout", input, 3000, 120, true))
          .isEqualTo(seconds);
      time.addAndGet(seconds * 1000);
    }
    assertThat(guard.inputLimit("run", "config", 100000)).isEqualTo(6000);
  }

  @Test
  void runAndConfigurationChangesAndSuccessfulSummaryResetTheirOwnFailureState() {
    guard.failed("run", "config", "summary_authentication", 40000, 3000, 120, false);
    assertThat(guard.decision("another-run", "config").allowed()).isTrue();
    assertThat(guard.decision("run", "changed-model").allowed()).isTrue();
    assertThat(guard.inputLimit("run", "changed-model", 100000)).isEqualTo(100000);
    guard.failed("run", "changed-model", "summary_timeout", 40000, 3000, 120, true);
    guard.succeeded("run");
    assertThat(guard.decision("run", "changed-model").allowed()).isTrue();
  }

  @Test
  void doesNotReduceInputForAuthenticationAndBoundsRetainedRunState() {
    guard.failed("oldest", "config", "summary_authentication", 40000, 3000, 120, false);
    assertThat(guard.inputLimit("oldest", "config", 100000)).isEqualTo(100000);
    for (int i = 0; i < 256; i++)
      guard.failed("run-" + i, "config", "summary_timeout", 40000, 3000, 120, true);
    assertThat(guard.decision("oldest", "config").allowed()).isTrue();
    assertThat(guard.decision("run-255", "config").allowed()).isFalse();
  }
}
