package dev.mikoto2000.rei.core.actionplan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * タスクを完了するための具体的なステップ列を保持する。
 *
 * <p>Task State（現在のタスク全体の状態）とは別に、具体的なステップの順序と状態を管理する。
 */
public class ActionPlan {

  /** 未着手。 */
  public static final String STATUS_TODO = "TODO";

  /** 進行中。 */
  public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

  /** 完了。 */
  public static final String STATUS_DONE = "DONE";

  /** 進行不能。 */
  public static final String STATUS_BLOCKED = "BLOCKED";

  /** 不要になった。 */
  public static final String STATUS_SKIPPED = "SKIPPED";

  /** 手順一覧。 */
  private final List<PlanStep> steps = new ArrayList<>();

  /** 次順序。 */
  private int nextOrder = 1;

  /** 現在のステップ一覧。 */
  public List<PlanStep> steps() {
    return List.copyOf(steps);
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
  public void restore(List<PlanStep> saved) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (saved.size() > 100
        || saved.stream().map(PlanStep::id).distinct().count() != saved.size()
        || saved.stream()
            .anyMatch(
                s ->
                    !List.of(
                            STATUS_TODO,
                            STATUS_IN_PROGRESS,
                            STATUS_DONE,
                            STATUS_BLOCKED,
                            STATUS_SKIPPED)
                        .contains(s.status()))
        || saved.stream().filter(s -> STATUS_IN_PROGRESS.equals(s.status())).count() > 1)
      throw new IllegalArgumentException("Invalid action plan");
    steps.clear();
    steps.addAll(saved);
    nextOrder = saved.stream().mapToInt(PlanStep::order).max().orElse(0) + 1;
  }

  /** plan が空かどうか。 */
  public boolean isEmpty() {
    return steps.isEmpty();
  }

  /** 現在 IN_PROGRESS のステップを返す。なければ空。 */
  public Optional<PlanStep> currentStep() {
    return steps.stream().filter(s -> STATUS_IN_PROGRESS.equals(s.status())).findFirst();
  }

  /** 次の TODO ステップを返す。なければ空。 */
  public Optional<PlanStep> nextStep() {
    return steps.stream().filter(s -> STATUS_TODO.equals(s.status())).findFirst();
  }

  /** ステップを追加する。 */
  public void addStep(String description) {
    String id = "step-" + nextOrder;
    steps.add(new PlanStep(id, description, STATUS_TODO, nextOrder, 0));
    nextOrder++;
  }

  /** ステップを開始する（IN_PROGRESS は常に 1 件のみ）。 */
  public void startStep(String stepId) {
    for (int i = 0; i < steps.size(); i++) {
      PlanStep step = steps.get(i);
      if (step.id().equals(stepId)) {
        steps.set(i, withStatus(step, STATUS_IN_PROGRESS));
      } else if (STATUS_IN_PROGRESS.equals(step.status())) {
        steps.set(i, withStatus(step, STATUS_TODO));
      }
    }
  }

  /** ステップを完了する。 */
  public void completeStep(String stepId) {
    updateStatus(stepId, STATUS_DONE);
  }

  /** ステップを進行不能にする。 */
  public void blockStep(String stepId) {
    updateStatus(stepId, STATUS_BLOCKED);
  }

  /** ステップを不要としてスキップする。 */
  public void skipStep(String stepId) {
    updateStatus(stepId, STATUS_SKIPPED);
  }

  /** 指定ステップの失敗回数を増やす。 */
  public void incrementFailure(String stepId) {
    for (int i = 0; i < steps.size(); i++) {
      PlanStep step = steps.get(i);
      if (step.id().equals(stepId)) {
        steps.set(
            i,
            new PlanStep(
                step.id(),
                step.description(),
                step.status(),
                step.order(),
                step.failureCount() + 1));
      }
    }
  }

  /** 全必須ステップが DONE または SKIPPED になっているか。 */
  public boolean isComplete() {
    return !steps.isEmpty()
        && steps.stream()
            .allMatch(s -> STATUS_DONE.equals(s.status()) || STATUS_SKIPPED.equals(s.status()));
  }

  /** LLM コンテキストに渡す簡潔な表現を組み立てる。 */
  public String renderForPrompt() {
    if (steps.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("## Action Plan\n\n");
    for (int i = 0; i < steps.size(); i++) {
      PlanStep step = steps.get(i);
      sb.append(i + 1)
          .append(". [")
          .append(step.status())
          .append("] ")
          .append(step.description())
          .append("\n");
    }
    return sb.toString();
  }

  /** 最新の内容を後続の処理に反映するため、状態を更新する。 */
  private void updateStatus(String stepId, String status) {
    for (int i = 0; i < steps.size(); i++) {
      PlanStep step = steps.get(i);
      if (step.id().equals(stepId)) {
        steps.set(i, withStatus(step, status));
      }
    }
  }

  /** 指定された状態を反映した情報を作成する。 */
  private PlanStep withStatus(PlanStep step, String status) {
    return new PlanStep(step.id(), step.description(), status, step.order(), step.failureCount());
  }
}
