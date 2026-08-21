package dev.mikoto2000.rei.core.actionplan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * タスクを完了するための具体的なステップ列を保持する。
 *
 * <p>Task State（現在のタスク全体の状態）とは別に、具体的なステップの順序と状態を管理する。</p>
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

  private final List<PlanStep> steps = new ArrayList<>();

  /** 現在のステップ一覧。 */
  public List<PlanStep> steps() {
    return List.copyOf(steps);
  }

  /** plan が空かどうか。 */
  public boolean isEmpty() {
    return steps.isEmpty();
  }

  /** 現在 IN_PROGRESS のステップを返す。なければ空。 */
  public Optional<PlanStep> currentStep() {
    return steps.stream().filter(s -> STATUS_IN_PROGRESS.equals(s.status())).findFirst();
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
      sb.append(i + 1).append(". [").append(step.status()).append("] ").append(step.description()).append("\n");
    }
    return sb.toString();
  }
}
