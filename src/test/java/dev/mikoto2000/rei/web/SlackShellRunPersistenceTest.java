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

/** Slackシェル実行状態の保存の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackShellRunPersistenceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** Slack限定シェル実行について、登録台帳再開の後も対象の情報が残ることを確認する。 */
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
