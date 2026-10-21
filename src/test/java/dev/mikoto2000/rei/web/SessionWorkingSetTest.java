package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.project.ProjectScopeConfiguration;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.core.working.WorkingSetConfiguration;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 会話作業中集合の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SessionWorkingSetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** Web APIによる操作で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void sessionsHaveIndependentWorkingSetsAndContinuationRestoresItsOwnFiles() {
    String previous = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", directory.toString());
    String project = UUID.randomUUID().toString();
    try (var application = new AnnotationConfigApplicationContext()) {
      application.registerBean(Clock.class, Clock::systemUTC);
      application.registerBean(
          AgentEventFactory.class, () -> new AgentEventFactory(Clock.systemUTC()));
      application.registerBean(AgentEventBus.class, () -> new InMemoryAgentEventBus());
      application.register(ProjectScopeConfiguration.class, WorkingSetConfiguration.class);
      application.refresh();
      var working = application.getBean(WorkingSet.class);
      var first =
          new AgentRunContext("one", "project:" + project + ":chat:first", directory, project);
      var second =
          new AgentRunContext("two", "project:" + project + ":chat:second", directory, project);
      try (var scope = AgentRunScope.open(first)) {
        assertThat(working.maxFiles()).isEqualTo(50);
        working.recordRead(directory.resolve("first.txt"));
      }
      try (var scope = AgentRunScope.open(second)) {
        assertThat(working.maxFiles()).isEqualTo(50);
        assertThat(working.getFiles()).isEmpty();
        working.recordRead(directory.resolve("second.txt"));
      }
      try (var scope = AgentRunScope.open(first)) {
        assertThat(working.getFiles()).hasSize(1);
        assertThat(working.getFiles().getFirst().path()).endsWith("first.txt");
      }
    } finally {
      if (previous == null) System.clearProperty("rei.data-dir");
      else System.setProperty("rei.data-dir", previous);
    }
  }
}
