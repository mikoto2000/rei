package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.temporal.AgentScheduleDispatcher;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 実行予定をWeb APIの操作として受け付ける。
 *
 * <p>Human controls for saved schedules; activation retains the dispatcher's opt-in gates.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/schedules")
public class ScheduleController {
  /** 保存と検索の窓口。 */
  private final PersistentAgentScheduler repository;

  /** 実行先の振り分け。 */
  private final AgentScheduleDispatcher dispatcher;

  /** 指定された値や利用先を使って、ScheduleControllerを初期化する。 */
  public ScheduleController(
      PersistentAgentScheduler repository, AgentScheduleDispatcher dispatcher) {
    this.repository = repository;
    this.dispatcher = dispatcher;
  }

  /**
   * Web APIによる操作で受け渡す実行間隔を表す。
   *
   * @param intervalMillis 実行間隔ミリ秒数
   * @param remaining 残量
   */
  public record Interval(long intervalMillis, int remaining) {}

  /**
   * Web APIによる操作で受け渡すInspectionを表す。
   *
   * @param schedule 実行予定
   * @param interval 実行間隔
   * @param cron 定期実行条件
   * @param eventTrigger イベント実行条件
   */
  public record Inspection(
      PersistentAgentScheduler.Entry schedule,
      Interval interval,
      PersistentAgentScheduler.Cron cron,
      PersistentAgentScheduler.EventTrigger eventTrigger) {}

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public List<PersistentAgentScheduler.Entry> list(@PathVariable String projectId) {
    return repository.list(projectId);
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public Inspection show(@PathVariable String projectId, @PathVariable String id) {
    var schedule = repository.get(projectId, id);
    var interval =
        repository
            .interval(projectId, id)
            .map(value -> new Interval(value.interval().toMillis(), value.remaining()))
            .orElse(null);
    return new Inspection(
        schedule,
        interval,
        repository.cron(projectId, id).orElse(null),
        repository.eventTrigger(projectId, id).orElse(null));
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  @GetMapping("/{id}/history")
  public List<PersistentAgentScheduler.History> history(
      @PathVariable String projectId, @PathVariable String id) {
    return repository.history(projectId, id);
  }

  /** Web APIによる操作で用いるactivateを返す。 */
  @PostMapping("/{id}/activate")
  public Inspection activate(@PathVariable String projectId, @PathVariable String id) {
    return control(
        () -> {
          repository.activate(projectId, id);
          return show(projectId, id);
        });
  }

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/{id}/cancel")
  public Inspection cancel(@PathVariable String projectId, @PathVariable String id) {
    return control(
        () -> {
          repository.cancel(projectId, id);
          return show(projectId, id);
        });
  }

  /**
   * Web APIによる操作で受け渡す状態の照合要求を表す。
   *
   * @param expectedRunId 期待する内容実行の識別子
   * @param acknowledgeUncertainSideEffects acknowledge不確実なSideEffects
   */
  public record ReconcileRequest(String expectedRunId, boolean acknowledgeUncertainSideEffects) {}

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  @PostMapping("/{id}/reconcile")
  public PersistentAgentScheduler.Entry reconcile(
      @PathVariable String projectId,
      @PathVariable String id,
      @RequestBody ReconcileRequest request) {
    if (request == null
        || !request.acknowledgeUncertainSideEffects()
        || request.expectedRunId() == null
        || request.expectedRunId().isBlank()
        || request.expectedRunId().length() > 128)
      throw new IllegalArgumentException(
          "Inspect effects and acknowledge the exact saved Run before reconciliation");
    return control(() -> dispatcher.reconcile(projectId, id, request.expectedRunId()));
  }

  /** Web APIによる操作で用いる制御を返す。 */
  private static <T> T control(Supplier<T> action) {
    try {
      return action.get();
    } catch (IllegalStateException conflict) {
      throw new OperationConflictException();
    }
  }
}
