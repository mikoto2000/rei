package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;

class ProjectRunStateStoreTest {
  @TempDir Path temp;
  @Test void cancellationIsNotRestoredAsFailure() {
    var project = new ProjectContext(UUID.randomUUID().toString(), "a", temp);
    var run = new AgentRunContext("run", project, "chat:main");
    var store = new ProjectRunStateStore(temp);
    store.onEvent(new AgentEventFactory(Clock.systemUTC()).runFailed("run",
        new ErrorInformation("Cancelled", "chat run cancelled", "cancelled")).withOwnership(run));
    assertThat(store.read(project.id()).orElseThrow().status()).isEqualTo("CANCELLED");
  }
  @Test void concurrentResultsKeepLatestCompletionPerProjectAcrossRestart() throws Exception {
    var store = new ProjectRunStateStore(temp);
    var a = UUID.randomUUID().toString();
    var b = UUID.randomUUID().toString();
    var type = dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE;
    var start = java.time.Instant.parse("2026-09-09T00:00:00Z");
    var newest = new dev.mikoto2000.rei.core.execution.CompletedExecution("new", a, "chat:a", type,
        "https://a.example", "new A", start, start.plusSeconds(2));
    var older = new dev.mikoto2000.rei.core.execution.CompletedExecution("old", a, "chat:a", type,
        "https://old.example", "old A", start, start.plusSeconds(1));
    var other = new dev.mikoto2000.rei.core.execution.CompletedExecution("b", b, "chat:b", type,
        "https://b.example", "B", start, start.plusSeconds(1));
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var futures = java.util.stream.Stream.of(newest, older, other).map(result -> executor.submit(() -> {
        gate.await(); store.saveCompleted(result); return null;
      })).toList();
      gate.countDown();
      for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    store.saveCompleted(older);
    var restored = new ProjectRunStateStore(temp);
    assertThat(restored.latestCompleted(a, type)).contains(newest);
    assertThat(restored.latestCompleted(b, type)).contains(other);
    assertThat(new dev.mikoto2000.rei.core.chat.ConversationInputRouter(Runnable::run, (c,p,q) -> {}).activeExecutions()).isEmpty();
  }
  @Test void lastRunStateRestoresWithoutAnEventStore() {
    var project = new ProjectContext(UUID.randomUUID().toString(), "a", temp);
    var run = new AgentRunContext("run", project, "chat:main");
    var store = new ProjectRunStateStore(temp);
    var events = new AgentEventFactory(Clock.systemUTC());
    store.onEvent(events.runStarted("run", "test", null).withOwnership(run));
    assertThat(store.read(project.id()).orElseThrow().status()).isEqualTo("RUNNING");
    store.onEvent(events.runCompleted("run", 1, null, null, null, null).withOwnership(run));
    assertThat(new ProjectRunStateStore(temp).read(project.id()).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(store.read(UUID.randomUUID().toString())).isEmpty();
  }
}
