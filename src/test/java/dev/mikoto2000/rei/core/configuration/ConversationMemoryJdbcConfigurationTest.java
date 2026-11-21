package dev.mikoto2000.rei.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.chatmemory.ConversationMemoryStore;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/** Verifies the same configured JDBC collaborator as the former Spring AI auto-configuration. */
class ConversationMemoryJdbcConfigurationTest {
  @TempDir Path directory;

  @Test
  void persistenceUsesTheInjectedTemplateAndItsQueryTimeout() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabaseConfiguration.class)
        .withPropertyValues(
            "test.memory.path=" + directory.resolve("memory.db"),
            "rei.embedding.enabled=false",
            "spring.ai.chat.memory.repository.jdbc.initialize-schema=always")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var template = context.getBean(JdbcTemplate.class);
          assertThat(template.getQueryTimeout()).isEqualTo(17);
          context
              .getBean(ConversationMemoryStore.class)
              .saveAll("a", List.of(new UserMessage("saved")));
          verify(template).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
          assertThat(context.getBean(ConversationMemoryStore.class).findByConversationId("a"))
              .extracting(message -> message.getText())
              .containsExactly("saved");
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class DatabaseConfiguration extends DataSourceConfiguration {
    private final String path;

    DatabaseConfiguration(@Value("${test.memory.path}") String path) {
      this.path = path;
    }

    @Bean
    @Override
    public DataSource dataSource() {
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + path);
      return source;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      var template = spy(new JdbcTemplate(dataSource));
      template.setQueryTimeout(17);
      return template;
    }
  }
}
