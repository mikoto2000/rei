package dev.mikoto2000.rei.core.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.CheckpointSavedPayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 再開用の記録保存先の結果と状態の契約を検証するテスト。 */
class CheckpointStoreTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  private CheckpointStore store(Instant start) {
    return new CheckpointStore(Clock.fixed(start, ZONE));
  }

  /** {@code checkpoint} の条件を再現するための補助情報を返す。 */
  private TurnCheckpoint checkpoint(String reason) {
    return new TurnCheckpoint(
        "task-1",
        "goal",
        "step-1",
        List.of(),
        List.of(),
        List.of(),
        null,
        null,
        null,
        reason,
        "2026-08-17T00:00:00Z");
  }

  /** 空の再開用の記録について、保有なし最新を保存することを確認する。 */
  @Test
  void emptyCheckpointStoreHasNoLatest() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    assertTrue(store.isEmpty());
    assertTrue(store.latest().isEmpty());
  }

  /** 実行状態の引き継ぎで、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void emptyCheckpointRendersBlank() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", store.renderForPrompt());
  }

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void checkpointCanBeSaved() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    TurnCheckpoint checkpoint =
        new TurnCheckpoint(
            "task-1",
            "UserService の Optional 対応",
            "step-2",
            List.of("再現テストを追加"),
            List.of("実装修正", "単体テスト"),
            List.of("src/UserService.java"),
            "再現テストが失敗した",
            null,
            "UserService.save() から再開",
            "TURN_END",
            "2026-08-17T00:00:00Z");
    store.save(checkpoint);
    assertFalse(store.isEmpty());
    assertTrue(store.latest().isPresent());
    assertEquals("task-1", store.latest().get().taskId());
  }

  /** 実行状態の引き継ぎで、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void latestCheckpointCanBeRetrieved() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("TURN_END"));
    store.save(checkpoint("LENGTH"));
    assertEquals("LENGTH", store.latest().get().reason());
  }

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void turnEndReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("TURN_END"));
    assertEquals("TURN_END", store.latest().get().reason());
  }

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void lengthReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("LENGTH"));
    assertEquals("LENGTH", store.latest().get().reason());
  }

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void blockedReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("BLOCKED"));
    assertEquals("BLOCKED", store.latest().get().reason());
  }

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completedReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("COMPLETED"));
    assertEquals("COMPLETED", store.latest().get().reason());
  }

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void checkpointIncludesWorkingSetInfo() {
    TurnCheckpoint checkpoint =
        new TurnCheckpoint(
            "task-1",
            "goal",
            "step-1",
            List.of(),
            List.of(),
            List.of("src/UserService.java", "src/UserServiceTest.java"),
            null,
            null,
            null,
            "TURN_END",
            "2026-08-17T00:00:00Z");
    assertEquals(2, checkpoint.workingFiles().size());
    assertTrue(checkpoint.workingFiles().contains("src/UserService.java"));
  }

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void checkpointIncludesTaskStateInfo() {
    TurnCheckpoint checkpoint =
        new TurnCheckpoint(
            "task-1",
            "goal",
            "step-1",
            List.of("再現テストを追加"),
            List.of("実装修正"),
            List.of(),
            "再現テストが失敗した",
            null,
            "実装修正から再開",
            "TURN_END",
            "2026-08-17T00:00:00Z");
    assertEquals("goal", checkpoint.goal());
    assertEquals(1, checkpoint.completedSummary().size());
    assertEquals(1, checkpoint.pendingSummary().size());
    assertEquals("実装修正から再開", checkpoint.resumeHint());
  }

  /** 実行状態の引き継ぎで、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void checkpointRendersResumeHint() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(
        new TurnCheckpoint(
            "task-1",
            "UserService の Optional 対応",
            "step-2",
            List.of(),
            List.of(),
            List.of(),
            "再現テストが失敗した",
            null,
            "UserService.save() から再開",
            "TURN_END",
            "2026-08-17T00:00:00Z"));
    String prompt = store.renderForPrompt();
    assertTrue(prompt.contains("## Resume Checkpoint"));
    assertTrue(prompt.contains("UserService の Optional 対応"));
    assertTrue(prompt.contains("UserService.save() から再開"));
  }

  /** 実行状態の引き継ぎで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void taskSwitchDoesNotReuseOldCheckpoint() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(
        new TurnCheckpoint(
            "task-1",
            "old goal",
            "step-1",
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            "TURN_END",
            "2026-08-17T00:00:00Z"));
    store.save(
        new TurnCheckpoint(
            "task-2",
            "new goal",
            "step-1",
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            "TURN_END",
            "2026-08-17T01:00:00Z"));
    assertEquals("task-2", store.latest().get().taskId());
    assertEquals("new goal", store.latest().get().goal());
  }

  /** 実行状態の引き継ぎで、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void savingCheckpointPublishesEvent() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    CheckpointStore store = new CheckpointStore(clock, new AgentEventFactory(clock), bus);

    store.save(
        new TurnCheckpoint(
            "task-1",
            "goal",
            "step-1",
            List.of(),
            List.of(),
            List.of("src/UserService.java", "src/UserServiceTest.java"),
            null,
            null,
            null,
            "TURN_END",
            "2026-08-17T00:00:00Z"));

    assertEquals(AgentEventType.CHECKPOINT_SAVED, events.getFirst().type());
    CheckpointSavedPayload payload = (CheckpointSavedPayload) events.getFirst().payload();
    assertEquals("task-1", payload.taskId());
    assertEquals("TURN_END", payload.reason());
    assertEquals(2, payload.workingFileCount());
  }
}
