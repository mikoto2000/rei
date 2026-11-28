package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AutonomousRegenerationPolicyTest {
  private static final Instant NOW = Instant.parse("2026-11-28T03:00:00Z");

  private static DefaultAutonomousActionPolicy policy() {
    return new DefaultAutonomousActionPolicy(
        true, true, 1, 1, Duration.ofHours(1), 3, Duration.ofHours(1));
  }

  private static AutonomousActionContext facts(
      boolean stopping, boolean active, int attempts, Instant lastAttempt, boolean targetMatches) {
    return new AutonomousActionContext(
        NOW,
        "did:plc:example",
        "rei.example",
        stopping,
        active,
        1,
        1,
        NOW,
        new AutonomousActionContext.Regeneration(attempts, lastAttempt, targetMatches));
  }

  @Test
  void existingDraftCanBeRegeneratedWhenNewDraftBudgetsAreAlreadyFull() {
    assertThat(policy().evaluate(facts(false, false, 0, null, true)).shouldConsider()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("denied")
  void regenerationUsesItsOwnLimitsAndKeepsSharedSafetyChecks(
      AutonomousActionContext context, String reason) {
    assertThat(policy().evaluate(context).shouldConsider()).isFalse();
    assertThat(policy().evaluate(context).reason()).isEqualTo(reason);
  }

  static java.util.stream.Stream<Arguments> denied() {
    return java.util.stream.Stream.of(
        Arguments.of(facts(true, false, 0, null, true), "stopping"),
        Arguments.of(facts(false, true, 0, null, true), "active_opportunity"),
        Arguments.of(facts(false, false, 0, null, false), "target_changed"),
        Arguments.of(facts(false, false, 3, null, true), "regeneration_limit"),
        Arguments.of(facts(false, false, 2, NOW.minusSeconds(3599), true), "regeneration_cooldown"),
        Arguments.of(facts(false, false, 1, NOW.plusSeconds(1), true), "regeneration_cooldown"));
  }

  @Test
  void finalAllowedRegenerationStartsAtCooldownBoundary() {
    assertThat(
            policy()
                .evaluate(facts(false, false, 2, NOW.minusSeconds(3600), true))
                .shouldConsider())
        .isTrue();
  }

  @Test
  void zeroLimitDisablesRegeneration() {
    var disabled =
        new DefaultAutonomousActionPolicy(
            true, true, 1, 1, Duration.ofHours(1), 0, Duration.ofHours(1));
    assertThat(disabled.evaluate(facts(false, false, 0, null, true)).reason())
        .isEqualTo("regeneration_limit");
  }

  @Test
  void bothFeatureFlagsStillApplyToRegeneration() {
    var context = facts(false, false, 0, null, true);
    assertThat(
            new DefaultAutonomousActionPolicy(false, true, 1, 1, Duration.ofHours(1))
                .evaluate(context)
                .reason())
        .isEqualTo("disabled");
    assertThat(
            new DefaultAutonomousActionPolicy(true, false, 1, 1, Duration.ofHours(1))
                .evaluate(context)
                .reason())
        .isEqualTo("bluesky_disabled");
    var noTarget =
        new AutonomousActionContext(NOW, "", "", false, false, 1, 1, NOW, context.regeneration());
    assertThat(policy().evaluate(noTarget).reason()).isEqualTo("target_not_configured");
  }
}
