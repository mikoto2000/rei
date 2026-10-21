package dev.mikoto2000.rei.event;

import java.util.List;

/**
 * 実行状況の通知で受け渡す文脈に使える予算評価済み通知内容を表す。
 *
 * @param inputBudget 入力に使える予算
 * @param totalTokens 使用トークン数の合計
 * @param included 採用した対象
 * @param dropped 除外された
 */
public record ContextBudgetEvaluatedPayload(
    int inputBudget, int totalTokens, List<String> included, List<String> dropped)
    implements AgentEventPayload {
  /** 受け取った情報からContextBudgetEvaluatedPayloadを作成する。 */
  public ContextBudgetEvaluatedPayload {
    included = included == null ? List.of() : List.copyOf(included);
    dropped = dropped == null ? List.of() : List.copyOf(dropped);
  }
}
