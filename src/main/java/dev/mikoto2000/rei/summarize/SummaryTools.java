package dev.mikoto2000.rei.summarize;

import dev.mikoto2000.rei.core.execution.ExecutionType;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.time.Instant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 要約をモデルから呼び出せるツールとして提供する。 */
@Component
public class SummaryTools {
  /** 状態一覧。 */
  private final ProjectRunStateStore states;

  /** 指定された値や利用先を使って、SummaryToolsを初期化する。 */
  public SummaryTools(ProjectRunStateStore states) {
    this.states = states;
  }

  /**
   * 情報の要約で受け渡す最新要約を表す。
   *
   * @param found 見つかった
   * @param message メッセージ
   * @param url 参照先のURL
   * @param summary 要約
   * @param completedAt 完了日時
   */
  public record LatestSummary(
      boolean found, String message, String url, String summary, Instant completedAt) {}

  /** 情報の要約で参照する直近要約を返す。 */
  @Tool(
      name = "getLastSummary",
      description =
          """
      現在の実行元プロジェクトで最後に成功した /summarize の元記事URL、要約本文、完了日時を取得する。
      「今要約した記事」「/summarize した記事」の参照や投稿依頼では、まずこのツールで対象を確認する。
      セッション変更・再起動後も参照可能。処理中・失敗した要約は含まない。
      found=false の場合は記事を特定できないので、フィードなどから推測せずURLを確認する。
      """)
  public LatestSummary getLastSummary() {
    var project = ProjectService.contextForOperation();
    if (project == null) return new LatestSummary(false, "実行元プロジェクトがありません", null, null, null);
    return states
        .latestCompleted(project.id(), ExecutionType.SUMMARIZE)
        .map(
            result ->
                new LatestSummary(
                    true, "最後に成功した要約", result.source(), result.result(), result.completedAt()))
        .orElseGet(() -> new LatestSummary(false, "このプロジェクトには完了した要約がありません", null, null, null));
  }
}
