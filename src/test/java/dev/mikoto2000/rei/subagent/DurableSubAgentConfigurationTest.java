package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.sqlite.SQLiteDataSource;

/** 永続化された補助エージェント機能の構成の結果と状態の契約を検証するテスト。 */
class DurableSubAgentConfigurationTest {
  /** 評価の合意について、両方永続化されたを必要とすることを確認する。 */
  @Test
  void consensusRequiresBothDurableAndConsensusOptIn() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("consensus.db"));
    var context =
        new ApplicationContextRunner()
            .withUserConfiguration(DurableSubAgentConfiguration.class)
            .withBean("dataSource", DataSource.class, () -> source)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(SubAgentProperties.class, SubAgentProperties::new)
            .withBean(
                dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class,
                () ->
                    org.mockito.Mockito.mock(
                        dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class))
            .withBean(
                dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class,
                () ->
                    org.mockito.Mockito.mock(
                        dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class))
            .withBean(SubAgentRunner.class, () -> org.mockito.Mockito.mock(SubAgentRunner.class))
            .withBean(
                SubAgentRegistry.class, () -> org.mockito.Mockito.mock(SubAgentRegistry.class));
    context
        .withPropertyValues("rei.subagents.consensus-enabled=true")
        .run(app -> assertThat(app).doesNotHaveBean(SubAgentConsensusService.class));
    context
        .withPropertyValues("rei.subagents.durable-enabled=true")
        .run(app -> assertThat(app).doesNotHaveBean(SubAgentConsensusService.class));
    context
        .withPropertyValues(
            "rei.subagents.durable-enabled=true", "rei.subagents.consensus-enabled=true")
        .run(app -> assertThat(app).hasSingleBean(SubAgentConsensusService.class));
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void disabledByDefaultAndEnabledOnlyByExplicitConfiguration() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("children.db"));
    var context =
        new ApplicationContextRunner()
            .withUserConfiguration(DurableSubAgentConfiguration.class)
            .withBean("dataSource", DataSource.class, () -> source)
            .withBean(Clock.class, Clock::systemUTC);
    context.run(app -> assertThat(app).doesNotHaveBean(DurableSubAgentRepository.class));
    context
        .withPropertyValues("rei.subagents.durable-enabled=true")
        .run(
            app -> {
              assertThat(app).hasSingleBean(DurableSubAgentRepository.class);
              assertThat(
                      org.springframework.jdbc.core.simple.JdbcClient.create(source)
                          .sql("SELECT COUNT(*) FROM subagent_checkpoints")
                          .query(Long.class)
                          .single())
                  .isZero();
            });
  }
}
