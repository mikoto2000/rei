package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 作業中集合検索イベントの結果と状態の契約を検証するテスト。 */
class WorkingSetSearchEventTest {
  /** 通知された出来事と順序を検証するための記録。 */
  private final AgentEventFactory events =
      new AgentEventFactory(Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));

  /** 実行状況の通知で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void startedAndCompletedShareTheSearchCorrelationId() {
    AgentEvent started =
        events.workingSetSearchStarted("ws-search-1", "ToolCallbackProvider", "searchAndRead", 2);
    AgentEvent completed = events.workingSetSearchCompleted("ws-search-1", 91, 18, 5, 2, 1, 2, 3);

    assertEquals(AgentEventType.WORKING_SET_SEARCH_STARTED, started.type());
    assertEquals(AgentEventType.WORKING_SET_SEARCH_COMPLETED, completed.type());
    assertEquals("ws-search-1", started.correlationId());
    assertEquals(started.correlationId(), completed.correlationId());
    WorkingSetSearchStartedPayload startPayload =
        (WorkingSetSearchStartedPayload) started.payload();
    assertEquals("ToolCallbackProvider", startPayload.query());
    WorkingSetSearchCompletedPayload payload =
        (WorkingSetSearchCompletedPayload) completed.payload();
    assertEquals(18, payload.hitCount());
    assertEquals(5, payload.candidateCount());
    assertEquals(2, payload.selectedCount());
    assertEquals(1, payload.alreadyPresentCount());
    assertTrue(payload.durationMs() >= 0);
  }

  /** 実行状況の通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void contextInjectedCapturesInjectedContextSize() {
    AgentEvent event = events.workingSetContextInjected(3, 429);

    assertEquals(AgentEventType.WORKING_SET_CONTEXT_INJECTED, event.type());
    WorkingSetContextInjectedPayload payload = (WorkingSetContextInjectedPayload) event.payload();
    assertEquals(3, payload.itemCount());
    assertEquals(429, payload.contextCharacters());
  }
}
