package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The opportunity policy grants consideration only, never publication. */
class AutonomousActionPolicyTest {
  @Test
  void disabledFeatureNeverAllowsConsideration() {
    var policy = new DefaultAutonomousActionPolicy(false, true, 1, 1, Duration.ofHours(1));
    assertThat(policy.evaluate(context(false, false, 0, 0, null)).shouldConsider()).isFalse();
  }

  @Test
  void blueskyCanBeDisabledIndependently() {
    var policy = new DefaultAutonomousActionPolicy(true, false, 1, 1, Duration.ofHours(1));
    assertThat(policy.evaluate(context(false, false, 0, 0, null)).reason())
        .isEqualTo("bluesky_disabled");
  }

  @ParameterizedTest
  @MethodSource("deniedFacts")
  void lifecycleLimitsAndTargetAreChecked(AutonomousActionContext facts, String reason) {
    var decision = enabled().evaluate(facts);
    assertThat(decision.shouldConsider()).isFalse();
    assertThat(decision.reason()).isEqualTo(reason);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> deniedFacts() {
    var time = Instant.parse("2026-11-28T03:00:00Z");
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(
                time, "did:plc:example", "rei.example", true, false, 0, 0, null),
            "stopping"),
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(
                time, "did:plc:example", "rei.example", false, true, 0, 0, null),
            "active_opportunity"),
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(time, "", "rei.example", false, false, 0, 0, null),
            "target_not_configured"),
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(time, "did:plc:example", "", false, false, 0, 0, null),
            "target_not_configured"),
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(
                time, "did:plc:example", "rei.example", false, false, 1, 0, null),
            "pending_limit"),
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(
                time, "did:plc:example", "rei.example", false, false, 0, 1, null),
            "daily_limit"),
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(
                time,
                "did:plc:example",
                "rei.example",
                false,
                false,
                0,
                0,
                time.minusSeconds(3599)),
            "cooldown"),
        org.junit.jupiter.params.provider.Arguments.of(
            new AutonomousActionContext(
                time, "did:plc:example", "rei.example", false, false, 0, 0, time.plusSeconds(1)),
            "cooldown"));
  }

  @Test
  void considerationIsAllowedAtCooldownBoundary() {
    var facts = context(false, false, 0, 0, Instant.parse("2026-11-28T02:00:00Z"));
    assertThat(enabled().evaluate(facts).shouldConsider()).isTrue();
  }

  private DefaultAutonomousActionPolicy enabled() {
    return new DefaultAutonomousActionPolicy(true, true, 1, 1, Duration.ofHours(1));
  }

  private AutonomousActionContext context(
      boolean stopping, boolean active, int pending, int generated, Instant attempted) {
    return new AutonomousActionContext(
        Instant.parse("2026-11-28T03:00:00Z"),
        "did:plc:example",
        "rei.example",
        stopping,
        active,
        pending,
        generated,
        attempted);
  }
}
