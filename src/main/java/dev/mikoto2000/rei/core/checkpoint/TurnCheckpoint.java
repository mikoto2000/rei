package dev.mikoto2000.rei.core.checkpoint;

import java.util.List;

/**
 * 再開に必要な要点を固定したスナップショット。
 *
 * <p>Task State / Working Set 等の完全コピーではなく、再開に必要な最小情報を保持する。</p>
 *
 * @param taskId        タスク識別子（任意）
 * @param goal          現在の目的
 * @param currentStep   現在のステップ
 * @param completedSummary 完了した作業の要約
 * @param pendingSummary   残りの作業の要約
 * @param workingFiles  作業中のファイル一覧
 * @param lastResult    最後の結果
 * @param lastFailure   最後の失敗
 * @param resumeHint    再開ヒント
 * @param reason        作成理由
 * @param createdAt     作成日時
 */
public record TurnCheckpoint(
    String taskId,
    String goal,
    String currentStep,
    List<String> completedSummary,
    List<String> pendingSummary,
    List<String> workingFiles,
    String lastResult,
    String lastFailure,
    String resumeHint,
    String reason,
    String createdAt) {

  /** LLM コンテキストに渡す簡潔な表現を組み立てる。 */
  public String renderForPrompt() {
    StringBuilder sb = new StringBuilder();
    sb.append("## Resume Checkpoint\n\n");
    if (goal != null && !goal.isBlank()) {
      sb.append("Goal: ").append(goal).append("\n");
    }
    if (currentStep != null && !currentStep.isBlank()) {
      sb.append("Previous step: ").append(currentStep).append("\n");
    }
    if (lastResult != null && !lastResult.isBlank()) {
      sb.append("Last result: ").append(lastResult).append("\n");
    }
    if (resumeHint != null && !resumeHint.isBlank()) {
      sb.append("Resume from: ").append(resumeHint).append("\n");
    }
    return sb.toString();
  }
}
