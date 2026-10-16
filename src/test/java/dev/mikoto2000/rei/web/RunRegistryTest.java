package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.run.RunFailure;
import dev.mikoto2000.rei.application.run.RunNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunRegistryTest {
  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-09-15T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  static AgentRunContext context(String id) {
    return new AgentRunContext(id, "session", Path.of("."), "project");
  }

  @Test
  void lifecycleHasImmutableTerminalStateAndMetadata() {
    var clock = new MutableClock();
    var registry = new RunRegistry(clock);
    registry.register(context("run"));
    assertThat(registry.get("run").status()).isEqualTo(RunStatus.QUEUED);
    assertThat(registry.get("run").startedAt()).isNull();
    assertThat(registry.transition("run", RunStatus.COMPLETED, null)).isFalse();
    assertThat(registry.transition("run", RunStatus.RUNNING, null)).isTrue();
    assertThat(registry.get("run").startedAt()).isEqualTo(clock.now);
    assertThat(registry.get("run").completedAt()).isNull();
    clock.now = clock.now.plusSeconds(1);
    assertThat(registry.transition("run", RunStatus.FAILED, new RunFailure("failure", "message")))
        .isTrue();
    assertThat(registry.get("run").failure().type()).isEqualTo("failure");
    assertThat(registry.get("run").completedAt()).isEqualTo(clock.now);
    for (var status : RunStatus.values())
      assertThat(registry.transition("run", status, null)).isFalse();
    assertThatThrownBy(() -> registry.get("unknown")).isInstanceOf(RunNotFoundException.class);
  }

  @Test
  void cancelAndCompleteRaceHasExactlyOneWinner() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(context("run"));
    registry.transition("run", RunStatus.RUNNING, null);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancel =
          executor.submit(
              () -> {
                start.await();
                return registry.transition("run", RunStatus.CANCELLED, null);
              });
      var complete =
          executor.submit(
              () -> {
                start.await();
                return registry.transition("run", RunStatus.COMPLETED, null);
              });
      start.countDown();
      assertThat(cancel.get(5, TimeUnit.SECONDS) ^ complete.get(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(registry.get("run").status().isTerminal()).isTrue();
  }

  @Test
  void retentionStartsAtTerminalAndDoesNotExtendOnReads() {
    var clock = new MutableClock();
    var registry = new RunRegistry(clock);
    registry.register(context("queued"));
    registry.register(context("done"));
    registry.transition("done", RunStatus.CANCELLED, null);
    clock.now = clock.now.plusSeconds(1799);
    assertThat(registry.purgeExpired()).isEmpty();
    assertThat(registry.get("done").status()).isEqualTo(RunStatus.CANCELLED);
    clock.now = clock.now.plusSeconds(1);
    assertThat(registry.purgeExpired()).containsExactly("done");
    assertThatThrownBy(() -> registry.get("done")).isInstanceOf(RunNotFoundException.class);
    assertThat(registry.get("queued").status()).isEqualTo(RunStatus.QUEUED);
  }
}
