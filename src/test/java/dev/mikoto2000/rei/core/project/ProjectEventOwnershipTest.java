package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** プロジェクトイベント所有権の結果と状態の契約を検証するテスト。 */
class ProjectEventOwnershipTest {
  /** 生成元および通知の連携先について、取得済みプロジェクトを保持することを確認する。 */
  @Test
  void factoryAndBusPreserveCapturedProjectOnEventsWithoutExplicitRunId() {
    var project = new ProjectContext(UUID.randomUUID().toString(), "a", Path.of("a"));
    var run = new AgentRunContext("run", project, "chat:main");
    var bus = new InMemoryAgentEventBus();
    List<AgentEvent> observed = new ArrayList<>();
    bus.subscribe(observed::add);
    try (var scope = AgentRunScope.open(run)) {
      bus.publish(new AgentEventFactory(Clock.systemUTC()).messageStarted("message", "assistant"));
    }
    assertThat(observed.getFirst().projectId()).isEqualTo(project.id());
    assertThat(observed.getFirst().runId()).isEqualTo("run");
  }
}
