package dev.mikoto2000.rei.subagent;

import java.time.Instant;
import java.util.List;

/**
 * 補助エージェントへの委譲で受け渡す補助エージェント結果を表す。
 *
 * @param agentId エージェント識別子
 * @param subAgentRunId 補助エージェント実行の識別子
 * @param status 状態
 * @param output 出力
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 * @param structuredOutput 項目に整理した内容出力
 * @param validationErrors 検証エラー一覧
 * @param repairAttempts 修復試行回数
 * @param validationHistory 検証履歴
 * @param modelRetryAttempts モデル再試行試行回数
 * @param modelRetryHistory モデル再試行履歴
 * @param toolRetryAttempts ツール再試行試行回数
 * @param toolRetryHistory ツール再試行履歴
 * @param durableTaskId 永続化されたタスク識別子
 */
public record SubAgentResult(
    String agentId,
    String subAgentRunId,
    Status status,
    String output,
    Instant startedAt,
    Instant completedAt,
    SubAgentOutput structuredOutput,
    List<ValidationError> validationErrors,
    int repairAttempts,
    List<List<ValidationError>> validationHistory,
    int modelRetryAttempts,
    List<String> modelRetryHistory,
    int toolRetryAttempts,
    List<String> toolRetryHistory,
    String durableTaskId) {
  /** 受け取った情報からSubAgentResultを作成する。 */
  public SubAgentResult {
    validationErrors = List.copyOf(validationErrors);
    validationHistory = validationHistory.stream().map(List::copyOf).toList();
    if (repairAttempts < 0 || repairAttempts > 3)
      throw new IllegalArgumentException("Invalid repair attempts");
    modelRetryHistory = List.copyOf(modelRetryHistory);
    if (modelRetryAttempts < 0 || modelRetryAttempts > 3 || modelRetryHistory.size() > 4)
      throw new IllegalArgumentException("Invalid model retry history");
    toolRetryHistory = List.copyOf(toolRetryHistory);
    if (toolRetryAttempts < 0 || toolRetryAttempts > 3 || toolRetryHistory.size() > 4)
      throw new IllegalArgumentException("Invalid Tool retry history");
  }

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentResult(
      String agentId,
      String subAgentRunId,
      Status status,
      String output,
      Instant startedAt,
      Instant completedAt,
      SubAgentOutput structuredOutput,
      List<ValidationError> validationErrors,
      int repairAttempts,
      List<List<ValidationError>> validationHistory,
      int modelRetryAttempts,
      List<String> modelRetryHistory,
      int toolRetryAttempts,
      List<String> toolRetryHistory) {
    this(
        agentId,
        subAgentRunId,
        status,
        output,
        startedAt,
        completedAt,
        structuredOutput,
        validationErrors,
        repairAttempts,
        validationHistory,
        modelRetryAttempts,
        modelRetryHistory,
        toolRetryAttempts,
        toolRetryHistory,
        null);
  }

  /** 指定された永続化されたタスクを反映した情報を作成する。 */
  public SubAgentResult withDurableTask(String id) {
    return new SubAgentResult(
        agentId,
        subAgentRunId,
        status,
        output,
        startedAt,
        completedAt,
        structuredOutput,
        validationErrors,
        repairAttempts,
        validationHistory,
        modelRetryAttempts,
        modelRetryHistory,
        toolRetryAttempts,
        toolRetryHistory,
        id);
  }

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentResult(
      String agentId,
      String subAgentRunId,
      Status status,
      String output,
      Instant startedAt,
      Instant completedAt,
      SubAgentOutput structuredOutput,
      List<ValidationError> validationErrors,
      int repairAttempts,
      List<List<ValidationError>> validationHistory,
      int modelRetryAttempts,
      List<String> modelRetryHistory) {
    this(
        agentId,
        subAgentRunId,
        status,
        output,
        startedAt,
        completedAt,
        structuredOutput,
        validationErrors,
        repairAttempts,
        validationHistory,
        modelRetryAttempts,
        modelRetryHistory,
        0,
        List.of());
  }

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentResult(
      String agentId,
      String subAgentRunId,
      Status status,
      String output,
      Instant startedAt,
      Instant completedAt,
      SubAgentOutput structuredOutput,
      List<ValidationError> validationErrors,
      int repairAttempts,
      List<List<ValidationError>> validationHistory) {
    this(
        agentId,
        subAgentRunId,
        status,
        output,
        startedAt,
        completedAt,
        structuredOutput,
        validationErrors,
        repairAttempts,
        validationHistory,
        0,
        List.of());
  }

  /** 指定されたモデル再試行回数を反映した情報を作成する。 */
  public SubAgentResult withModelRetries(int attempts, List<String> history) {
    return new SubAgentResult(
        agentId,
        subAgentRunId,
        status,
        output,
        startedAt,
        completedAt,
        structuredOutput,
        validationErrors,
        repairAttempts,
        validationHistory,
        attempts,
        history,
        toolRetryAttempts,
        toolRetryHistory,
        durableTaskId);
  }

  /** 指定されたツール再試行回数を反映した情報を作成する。 */
  public SubAgentResult withToolRetries(int attempts, List<String> history) {
    return new SubAgentResult(
        agentId,
        subAgentRunId,
        status,
        output,
        startedAt,
        completedAt,
        structuredOutput,
        validationErrors,
        repairAttempts,
        validationHistory,
        modelRetryAttempts,
        modelRetryHistory,
        attempts,
        history,
        durableTaskId);
  }

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentResult(
      String agentId,
      String subAgentRunId,
      Status status,
      String output,
      Instant startedAt,
      Instant completedAt,
      SubAgentOutput structuredOutput,
      List<ValidationError> validationErrors) {
    this(
        agentId,
        subAgentRunId,
        status,
        output,
        startedAt,
        completedAt,
        structuredOutput,
        validationErrors,
        0,
        List.of());
  }

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentResult(
      String agentId,
      String subAgentRunId,
      Status status,
      String output,
      Instant startedAt,
      Instant completedAt) {
    this(agentId, subAgentRunId, status, output, startedAt, completedAt, null, List.of());
  }

  /** 補助エージェントへの委譲における状態の選択肢。 */
  public enum Status {
    /** 完了。 */
    COMPLETED,
    /** 失敗。 */
    FAILED,
    /** 最大手順一覧上限超過。 */
    MAX_STEPS_EXCEEDED,
    /** タイムアウト。 */
    TIMEOUT,
    /** キャンセル済み。 */
    CANCELLED,
    /** 不明エージェント。 */
    UNKNOWN_AGENT
  }
}
