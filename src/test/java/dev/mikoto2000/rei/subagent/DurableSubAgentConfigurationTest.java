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
