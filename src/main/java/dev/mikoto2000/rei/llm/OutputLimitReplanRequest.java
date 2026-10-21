package dev.mikoto2000.rei.llm;

/**
 * 用途に応じたモデル利用で受け渡す出力上限計画の見直し要求を表す。
 *
 * @param originalUserRequest 元の利用者要求
 * @param currentGoal 現在目標
 * @param progressSoFar 進捗SoFar
 * @param partialOutput 部分的な結果出力
 * @param replanCount 計画の見直し件数
 * @param maxReplansPerGoal 最大計画の見直し回数ごとの目標
 * @param remainingLlmCalls 残量言語モデル呼び出し回数
 */
public record OutputLimitReplanRequest(
    String originalUserRequest,
    String currentGoal,
    String progressSoFar,
    String partialOutput,
    int replanCount,
    int maxReplansPerGoal,
    int remainingLlmCalls) {}
