package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 会話の実行で受け渡す活動中実行を表す。
 *
 * <p>Immutable runtime view. Identity is captured at dispatch, never inferred from Shell selection.
 *
 * @param runId 実行の識別子
 * @param projectId 所属プロジェクトの識別子
 * @param conversationId 会話の識別子
 * @param projectRoot 作業対象のルートディレクトリ
 * @param requestSummary 要求要約
 * @param startedAt 開始日時
 * @param status 状態
 */
public record ActiveRun(
    String runId,
    String projectId,
    String conversationId,
    Path projectRoot,
    String requestSummary,
    Instant startedAt,
    Status status) {
  /** 会話の実行における状態の選択肢。 */
  public enum Status {
    /** 実行中。 */
    RUNNING
  }

  /** 会話の実行で受け渡す情報を、指定された内容から作成する。 */
  static ActiveRun of(AgentRunContext context, String request) {
    return new ActiveRun(
        context.runId(),
        context.projectId(),
        context.conversationId(),
        context.projectRoot(),
        summary(request),
        Instant.now(),
        Status.RUNNING);
  }

  /** 会話の実行で用いる要約を返す。 */
  public static String summary(String request) {
    String text = request.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    return text.codePointCount(0, text.length()) <= 80
        ? text
        : text.substring(0, text.offsetByCodePoints(0, 77)) + "...";
  }
}
