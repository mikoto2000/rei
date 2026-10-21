package dev.mikoto2000.rei.llm;

import java.util.List;

/**
 * 用途に応じたモデル利用で受け渡す出力上限計画の見直し計画を表す。
 *
 * @param subgoals 小さな目標
 * @param finalGoal 最終目標
 */
public record OutputLimitReplanPlan(List<OutputLimitReplanSubgoal> subgoals, String finalGoal) {
  /** 受け取った情報からOutputLimitReplanPlanを作成する。 */
  public OutputLimitReplanPlan {
    subgoals = List.copyOf(subgoals);
  }
}
