package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class DurableSubAgentConfigurationTest {
  @Test void consensusRequiresBothDurableAndConsensusOptIn() {
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("consensus.db"));
    var context=new ApplicationContextRunner().withUserConfiguration(DurableSubAgentConfiguration.class)
      .withBean("dataSource",DataSource.class,()->source).withBean(Clock.class,Clock::systemUTC)
      .withBean(SubAgentProperties.class,SubAgentProperties::new)
      .withBean(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class,()->org.mockito.Mockito.mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class))
      .withBean(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class,()->org.mockito.Mockito.mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class))
      .withBean(SubAgentRunner.class,()->org.mockito.Mockito.mock(SubAgentRunner.class))
      .withBean(SubAgentRegistry.class,()->org.mockito.Mockito.mock(SubAgentRegistry.class));
    context.withPropertyValues("rei.subagents.consensus-enabled=true").run(app->assertThat(app).doesNotHaveBean(SubAgentConsensusService.class));
    context.withPropertyValues("rei.subagents.durable-enabled=true").run(app->assertThat(app).doesNotHaveBean(SubAgentConsensusService.class));
    context.withPropertyValues("rei.subagents.durable-enabled=true","rei.subagents.consensus-enabled=true")
      .run(app->assertThat(app).hasSingleBean(SubAgentConsensusService.class));
  }
  @TempDir Path directory;
  @Test void disabledByDefaultAndEnabledOnlyByExplicitConfiguration() {
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("children.db"));
    var context=new ApplicationContextRunner().withUserConfiguration(DurableSubAgentConfiguration.class)
        .withBean("dataSource",DataSource.class,()->source).withBean(Clock.class,Clock::systemUTC);
    context.run(app->assertThat(app).doesNotHaveBean(DurableSubAgentRepository.class));
    context.withPropertyValues("rei.subagents.durable-enabled=true").run(app->{
      assertThat(app).hasSingleBean(DurableSubAgentRepository.class);
      assertThat(org.springframework.jdbc.core.simple.JdbcClient.create(source).sql("SELECT COUNT(*) FROM subagent_checkpoints").query(Long.class).single()).isZero();
    });
  }
}
