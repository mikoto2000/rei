package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクトイベント境界条件の結果と状態の契約を検証するテスト。 */
class ProjectEventBoundaryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
  AgentEvent event(String project) {
    return new AgentEvent(
        UUID.randomUUID().toString(),
        0,
        Instant.EPOCH,
        AgentEventType.AGENT_RUN_COMPLETED,
        1,
        "s",
        null,
        "r",
        null,
        null,
        new AgentRunCompletedPayload("r", 1),
        project);
  }

  /** 識別情報を維持することを確認する。 */
  @Test
  void boundaryPersistsExactlyOnceAndNestedBoundariesKeepIdentity() {
    var bus = new InMemoryAgentEventBus();
    var store = new ProjectAgentEventStore(dir);
    String project = UUID.randomUUID().toString();
    try (var subscriber = new ProjectAgentEventSubscriber(bus, store)) {
      var first = event(project);
      var second = event(project);
      bus.subscribe(
          e -> {
            if (e.id().equals(first.id())) bus.publishBoundary(second);
          });
      bus.publishBoundary(first);
      assertEquals(
          List.of(first.id(), second.id()),
          store.recent(project, 10).stream().map(AgentEvent::id).toList());
    }
  }

  /** 実行状況の通知で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void durabilityFailureReachesBoundaryCallerBeforePublishing() throws Exception {
    Files.writeString(dir.resolve("projects"), "not a directory");
    var bus = new InMemoryAgentEventBus();
    var store = new ProjectAgentEventStore(dir);
    var published = new ArrayList<AgentEvent>();
    bus.subscribe(published::add);
    try (var subscriber = new ProjectAgentEventSubscriber(bus, store)) {
      assertThrows(
          IllegalStateException.class,
          () -> bus.publishBoundary(event(UUID.randomUUID().toString())));
      assertTrue(published.isEmpty());
    }
  }
}
