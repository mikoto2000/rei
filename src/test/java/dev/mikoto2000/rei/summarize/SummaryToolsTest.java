package dev.mikoto2000.rei.summarize;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.execution.CompletedExecution;
import dev.mikoto2000.rei.core.execution.ExecutionType;
import dev.mikoto2000.rei.core.project.*;
import static org.assertj.core.api.Assertions.*;

class SummaryToolsTest {
  @TempDir Path directory;

  @Test void retrievesPersistedUrlAndSummaryAcrossSessionsUsingExecutingProject() throws Exception {
    var projects = new ProjectService(directory, new ProjectRegistry(directory.resolve("projects.json")));
    var project = projects.currentContext();
    var states = new ProjectRunStateStore(directory);
    var now = Instant.now();
    states.saveCompleted(new CompletedExecution("summary", project.id(), project.conversationId("chat:main"),
        ExecutionType.SUMMARIZE, "https://example.com/article", "記事の要約", now, now));
    var tools = new SummaryTools(new ProjectRunStateStore(directory));
    var run = new AgentRunContext("run", project.conversationId("chat:new-session"), project.root(), project.id());
    try (var client = ProjectClientScope.open(projects.newClient())) {
      projects.cd(java.nio.file.Files.createDirectory(directory.resolve("other")).toString());
      assertThat(tools.getLastSummary().found()).isFalse();
      try (var scope = AgentRunScope.open(run)) {
        var result = tools.getLastSummary();
        assertThat(result.found()).isTrue();
        assertThat(result.url()).isEqualTo("https://example.com/article");
        assertThat(result.summary()).isEqualTo("記事の要約");
        assertThat(result.completedAt()).isEqualTo(now);
      }
    }
  }

  @Test void missingSummaryDoesNotFallBackToAnotherProject() {
    var tools = new SummaryTools(new ProjectRunStateStore(directory));
    assertThat(tools.getLastSummary().found()).isFalse();
    var run = new AgentRunContext("run", "chat:test", directory, UUID.randomUUID().toString());
    try (var scope = AgentRunScope.open(run)) {
      assertThat(tools.getLastSummary().found()).isFalse();
      assertThat(tools.getLastSummary().url()).isNull();
    }
  }
}
