package dev.mikoto2000.rei.summarize;

import java.time.Instant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.execution.ExecutionType;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.project.ProjectService;

@Component
public class SummaryTools {
  private final ProjectRunStateStore states;

  public SummaryTools(ProjectRunStateStore states) { this.states = states; }

  public record LatestSummary(boolean found, String message, String url, String summary, Instant completedAt) {}

  @Tool(name = "getLastSummary", description = """
      現在の実行元プロジェクトで最後に成功した /summarize の元記事URL、要約本文、完了日時を取得する。
      「今要約した記事」「/summarize した記事」の参照や投稿依頼では、まずこのツールで対象を確認する。
      セッション変更・再起動後も参照可能。処理中・失敗した要約は含まない。
      found=false の場合は記事を特定できないので、フィードなどから推測せずURLを確認する。
      """)
  public LatestSummary getLastSummary() {
    var project = ProjectService.contextForOperation();
    if (project == null) return new LatestSummary(false, "実行元プロジェクトがありません", null, null, null);
    return states.latestCompleted(project.id(), ExecutionType.SUMMARIZE)
        .map(result -> new LatestSummary(true, "最後に成功した要約", result.source(), result.result(), result.completedAt()))
        .orElseGet(() -> new LatestSummary(false, "このプロジェクトには完了した要約がありません", null, null, null));
  }
}
