package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 作業状況の把握に必要な作業状況エージェント根拠取得元を扱う。
 *
 * <p>Legacy OS contribution retains IDs only; the optional temporal supplement uses bounded
 * existing result summaries.
 */
public final class ActivityAgentEvidenceSource
    implements ActivityEvidenceSource, AgentEventListener, AutoCloseable {
  /** 直近。 */
  private final Deque<ActivityEvidence.RecentEvent> recent = new ArrayDeque<>();

  /** 実行一覧。 */
  private final Deque<WorkActivityInference.Execution> executions = new ArrayDeque<>();

  /** 購読。 */
  private final AgentEventBus.Subscription subscription;

  /** 時系列有効状態。 */
  private final java.util.function.BooleanSupplier temporalEnabled;

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityAgentEvidenceSource(AgentEventBus bus) {
    this(bus, () -> true);
  }

  /** 指定された値や利用先を使って、ActivityAgentEvidenceSourceを初期化する。 */
  public ActivityAgentEvidenceSource(
      AgentEventBus bus, java.util.function.BooleanSupplier temporalEnabled) {
    this.temporalEnabled = temporalEnabled;
    subscription = bus == null ? null : bus.subscribe(this);
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  @Override
  public synchronized void onEvent(AgentEvent event) {
    if (event == null || event.projectId() == null) return;
    String tool, summary, outcome;
    boolean temporal = temporalEnabled.getAsBoolean();
    if (event.payload() instanceof ToolCompletedPayload p) {
      tool = p.toolName();
      summary = temporal ? TemporalActivityEvidence.clean(p.resultSummary()) : "";
      outcome = "COMPLETED";
    } else if (event.payload() instanceof ToolFailedPayload p) {
      tool = p.toolName();
      summary = "";
      outcome = "FAILED";
    } else return;
    if (tool == null) return;
    String kind =
        switch (tool) {
          case "runCommand", "executeExternalProgram" -> "SHELL";
          case "writeMultiFile", "applyTextDiff" -> "FILE_EDIT";
          default -> null;
        };
    if (kind == null || event.projectId() == null) return;
    if (temporal) {
      executions.addLast(
          new WorkActivityInference.Execution(
              event.id(),
              event.timestamp(),
              event.projectId(),
              kind,
              "REI",
              outcome,
              summary,
              event.sessionId(),
              event.turnId(),
              event.runId()));
      while (executions.size() > 64) executions.removeFirst();
    } else executions.clear();
    if ("COMPLETED".equals(outcome)) {
      recent.addLast(
          new ActivityEvidence.RecentEvent(
              event.timestamp(),
              event.projectId(),
              kind,
              event.id(),
              event.sessionId(),
              event.turnId(),
              event.runId()));
      while (recent.size() > 16) recent.removeFirst();
    }
  }

  /** 判断に必要な情報を集め、後続の処理へ渡す。 */
  @Override
  public synchronized Contribution collect(Instant at) {
    recent.removeIf(e -> e.at().isBefore(at.minusSeconds(120)));
    return new Contribution("", "", recent.stream().filter(e -> !e.at().isAfter(at)).toList());
  }

  /** 作業状況の把握で用いる実行一覧を返す。 */
  public synchronized List<WorkActivityInference.Execution> executions(
      Instant at, int windowSeconds) {
    if (!temporalEnabled.getAsBoolean()) {
      executions.clear();
      return List.of();
    }
    executions.removeIf(e -> e.at().isBefore(at.minusSeconds(600)));
    return executions.stream()
        .filter(e -> !e.at().isBefore(at.minusSeconds(windowSeconds)) && !e.at().isAfter(at))
        .toList();
  }

  /** 作業状況の把握で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    if (subscription != null) subscription.unsubscribe();
  }
}
