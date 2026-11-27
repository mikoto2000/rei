package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.storage.StorageMigrationConfiguration;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("integration")
class AutonomousActionConfigurationTest {
  @TempDir Path root;

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            StorageMigrationConfiguration.class, AutonomousActionConfiguration.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(AgentEventBus.class, InMemoryAgentEventBus::new)
        .withPropertyValues("rei.data-dir=" + root);
  }

  @Test
  void foundationIsAvailableWithoutAChatModelAndRemainsDisabled() {
    context()
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(AutonomousDraftStore.class);
          assertThat(context.getBean(AutonomousActionProperties.class).enabled()).isFalse();
          var result =
              context
                  .getBean(AutonomousDraftStore.class)
                  .reserve(
                      "disabled", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example");
          assertThat(result.decision().reason()).isEqualTo("disabled");
          assertThat(result.claim()).isNull();
        });
  }

  @Test
  void unknownSettingsDoNotSilentlyEnableOrBypassLimits() {
    context()
        .withPropertyValues("rei.autonomous-action.bluesky.unknown=true")
        .run(context -> assertThat(context).hasFailed());
  }
}
