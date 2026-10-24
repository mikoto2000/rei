package dev.mikoto2000.rei.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Evidence;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Operation;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.OperationStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.stagnation.InformationLedger;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.sqlite.SQLiteDataSource;

/** 長いレビューで確定済み操作が上限を埋めても、未確定操作と再開証拠を維持する。 */
@Tag("integration")
class CheckpointOperationCapacityTest {
  /** 実データと隔離した保存先。 */
  @TempDir Path root;

  /** 不変リビジョンを保持する検証用リポジトリ。 */
  PersistentCheckpointRepository repository;

  /** 実際の境界保存を呼び出すサービス。 */
  PersistentCheckpointService service;

  /** 検証用イベント境界。 */
  final InMemoryAgentEventBus bus = new InMemoryAgentEventBus();

  /** 重複しないイベントを作る生成元。 */
  final AgentEventFactory events = new AgentEventFactory(Clock.systemUTC());

  /** 検証用の実行所有者。 */
  AgentRunContext owner;

  /** 整理前後で不変であるべき進捗と消費済み予算。 */
  final ProgressState progress =
      new ProgressState(
          new InformationLedger().snapshot(),
          new OutputLimitRunBudget.Snapshot(1, 457, 12345, false, 2, 3),
          12,
          3);

  /** 各検証の依存先と保存先を用意する。 */
  @BeforeEach
  void setup() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
    var settings = new CheckpointProperties();
    repository = new PersistentCheckpointRepository(source, settings);
    var factory = new StaticListableBeanFactory();
    service =
        new PersistentCheckpointService(
            repository,
            new CheckpointReconciler(null),
            settings,
            bus,
            factory.getBeanProvider(ConversationInputRouter.class),
            factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
            factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.actionplan.ActionPlan.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.working.WorkingSet.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.taskstate.TaskState.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.checkpoint.CheckpointStore.class));
    owner = new AgentRunContext("run", "session", root, "project");
  }

  /** イベント購読を解除する。 */
  @AfterEach
  void close() {
    service.close();
  }

  /** 従来上限まで埋まった成功記録を用意する。 */
  List<Operation> succeeded() {
    var result = new ArrayList<Operation>();
    for (int i = 0; i < 500; i++)
      result.add(
          new Operation(
              "call-" + i, "readMultiFile", OperationStatus.SUCCEEDED, "event-" + i, "run"));
    return result;
  }

  /** 承認待ちの保存状態からサービスを再開する。 */
  void install(List<Operation> operations, List<Evidence> evidence) {
    var fields =
        repository.fields(
            PersistentCheckpoint.initial(
                "task", "project", "session", "run", root, "review all chapters"));
    fields.put("status", "WAITING");
    fields.put("operations", operations);
    fields.put("evidence", evidence);
    fields.put("progress", progress);
    repository.save(repository.fields(fields), 0, "seed");
    service.start(owner, "review all chapters");
  }

  /** 保存し直された最新状態を読む。 */
  PersistentCheckpoint state() {
    return repository.get("project", "task");
  }

  /** 古い成功だけを整理し、直近の操作・進捗・再生防止を保持する。 */
  @Test
  void oldSuccessDoesNotBlockNextReadAndProgressRemainsExact() {
    var original = succeeded();
    install(original, List.of());
    var event = events.toolStarted("next", "readMultiFile", "unread range").withOwnership(owner);
    assertThatCode(() -> bus.publishBoundary(event)).doesNotThrowAnyException();
    assertThat(state().operations()).hasSize(500).doesNotContain(original.getFirst());
    assertThat(state().operations()).containsAll(original.subList(436, 500));
    assertThat(state().operations().getLast().toolCallId()).isEqualTo("next");
    assertThat(state().progress()).isEqualTo(progress);
    long revision = state().revision();
    bus.publishBoundary(event);
    assertThat(state().revision()).isEqualTo(revision);
    assertThat(repository.recordedEvent("project", "task", "seed")).isTrue();
  }

  /** 証拠参照は実行と呼び出しの組み合わせで保護する。 */
  @Test
  void evidenceProtectsItsExactRunAndCallIdentity() {
    var original = succeeded();
    original.set(
        0, new Operation("shared", "readMultiFile", OperationStatus.SUCCEEDED, "first", "old"));
    original.set(
        1, new Operation("shared", "readMultiFile", OperationStatus.SUCCEEDED, "second", "run"));
    var evidence = new Evidence("RAW_RESULT_REFERENCE", "raw-ref", "first", "old", "shared");
    install(original, List.of(evidence));
    assertThatCode(
            () ->
                bus.publishBoundary(
                    events.toolStarted("next", "readMultiFile", "range").withOwnership(owner)))
        .doesNotThrowAnyException();
    assertThat(state().operations()).contains(original.getFirst()).doesNotContain(original.get(1));
    assertThat(state().evidence()).contains(evidence);
  }

  /** 結果未確定と失敗の操作を整理対象にしない。 */
  @Test
  void incompleteAndFailedOperationsAreNeverEvicted() {
    var original = succeeded();
    var statuses =
        List.of(
            OperationStatus.PLANNED,
            OperationStatus.STARTED,
            OperationStatus.UNKNOWN,
            OperationStatus.FAILED);
    for (int i = 0; i < statuses.size(); i++)
      original.set(
          i,
          new Operation(
              "pending-" + i, "writeMultiFile", statuses.get(i), "pending-event-" + i, "run"));
    install(original, List.of());
    assertThatCode(
            () ->
                bus.publishBoundary(
                    events.toolStarted("next", "readMultiFile", "range").withOwnership(owner)))
        .doesNotThrowAnyException();
    assertThat(state().operations())
        .containsAll(original.subList(0, 4))
        .doesNotContain(original.get(4));
    assertThat(state().progress()).isEqualTo(progress);
  }

  /** 安全に整理できない場合は、保存状態を変えず次の実行を拒否する。 */
  @Test
  void fullUnresolvedCapacityStillStopsBeforeNewTool() {
    var operations = new ArrayList<Operation>();
    for (int i = 0; i < 500; i++)
      operations.add(
          new Operation(
              "pending-" + i, "writeMultiFile", OperationStatus.UNKNOWN, "event-" + i, "run"));
    install(operations, List.of());
    assertThatThrownBy(
            () ->
                bus.publishBoundary(
                    events.toolStarted("next", "readMultiFile", "range").withOwnership(owner)))
        .hasMessageContaining("operation capacity");
    assertThat(state().operations()).isEqualTo(operations);
    assertThat(state().progress()).isEqualTo(progress);
  }
}
