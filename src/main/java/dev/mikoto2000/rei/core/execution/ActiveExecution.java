package dev.mikoto2000.rei.core.execution;

import dev.mikoto2000.rei.core.chat.ActiveRun;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 処理の実行制御で受け渡す活動中実行を表す。
 *
 * @param id 識別子
 * @param projectId 所属プロジェクトの識別子
 * @param conversationId 会話の識別子
 * @param projectRoot 作業対象のルートディレクトリ
 * @param type 種別
 * @param summary 要約
 * @param startedAt 開始日時
 */
public record ActiveExecution(
    String id,
    String projectId,
    String conversationId,
    Path projectRoot,
    ExecutionType type,
    String summary,
    Instant startedAt) {
  /** 対象の現在の状態を、照会用の情報として返す。 */
  public String status() {
    return "RUNNING";
  }

  /** 処理の実行制御で用いる起点エージェントを返す。 */
  public static ActiveExecution fromAgent(ActiveRun run) {
    return new ActiveExecution(
        run.runId(),
        run.projectId(),
        run.conversationId(),
        run.projectRoot(),
        ExecutionType.AGENT,
        run.requestSummary(),
        run.startedAt());
  }
}
