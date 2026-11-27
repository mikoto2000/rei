package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.run.RunRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlanRunRegistryConfigurationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void planUsesOneRegistryWithEitherExistingHostOrStandaloneShell(boolean web, boolean slack) {
    var shared = new RunRegistry(Clock.systemUTC());
    var runner =
        runner().withPropertyValues("rei.web.enabled=" + web, "rei.slack.enabled=" + slack);
    if (web || slack) runner = runner.withBean("hostRunRegistry", RunRegistry.class, () -> shared);
    runner.run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(RunRegistry.class);
      if (web || slack) assertThat(context.getBean(RunRegistry.class)).isSameAs(shared);
    });
  }

  @Test
  void anExplicitRegistryRemainsTheSingleAuthorityInTheShell() {
    var shared = new RunRegistry(Clock.systemUTC());
    runner()
        .withBean("customRunRegistry", RunRegistry.class, () -> shared)
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(RunRegistry.class);
          assertThat(context.getBean(RunRegistry.class)).isSameAs(shared);
        });
  }

  @Test
  void disabledPlanDoesNotCreateARunRegistry() {
    runner()
        .withPropertyValues("rei.chatgpt-plan.enabled=false")
        .run(context -> {
          assertThat(context).hasNotFailed().doesNotHaveBean(RunRegistry.class);
        });
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(PlanConfiguration.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withPropertyValues(
            "rei.chatgpt-plan.enabled=true",
            "rei.chatgpt-plan.credential-directory=" + directory.resolve("credentials"));
  }
}
