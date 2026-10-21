package dev.mikoto2000.rei.event;

import java.util.List;

/**
 * 実行状況の通知で受け渡す文脈に使える予算余白を除いた通知内容を表す。
 *
 * @param inputBudget 入力に使える予算
 * @param totalTokens 使用トークン数の合計
 * @param dropped 除外された
 */
public record ContextBudgetTrimmedPayload(int inputBudget, int totalTokens, List<String> dropped)
    implements AgentEventPayload {
  /** 受け取った情報からContextBudgetTrimmedPayloadを作成する。 */
  public ContextBudgetTrimmedPayload {
    dropped = dropped == null ? List.of() : List.copyOf(dropped);
  }
}
