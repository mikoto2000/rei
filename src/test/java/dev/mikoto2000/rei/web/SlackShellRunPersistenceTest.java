package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class SlackShellRunPersistenceTest {
  @TempDir Path dir;

  @Test
  void slackOnlyShellRunSurvivesRegistryRestartWithWebEnabled() {
    var clock = Clock.systemUTC();
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("storage.db"));
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("source", source);
    var owner = new AgentRunContext("slack-run", "session", dir, "project");
    try (var registry =
        new WebApiConfiguration()
            .webRunRegistry(
                clock,
                beans.getBeanProvider(javax.sql.DataSource.class),
                false,
                false,
                false,
                true)) {
      registry.register(owner);
    }
    try (var restored = new RunRegistry(clock, source)) {
      assertEquals(owner, restored.get(owner.runId()).context());
    }
  }
}
