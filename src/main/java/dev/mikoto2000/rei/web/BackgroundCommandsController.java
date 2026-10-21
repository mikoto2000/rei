package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.BackgroundRunSubmitService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** バックグラウンドコマンド一覧をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class BackgroundCommandsController {
  /** 実行一覧。 */
  private final BackgroundRunSubmitService runs;

  /** 指定された値や利用先を使って、BackgroundCommandsControllerを初期化する。 */
  public BackgroundCommandsController(BackgroundRunSubmitService runs) {
    this.runs = runs;
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す画像を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param prompt プロンプト
   * @param output 出力
   * @param model モデル
   * @param size 大きさ
   * @param raw 元の
   */
  public record Image(
      String projectId, String prompt, String output, String model, String size, Boolean raw)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す要約を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param url 参照先のURL
   */
  public record Summary(String projectId, String url) implements StrictApiRequest {}

  /** Web APIによる操作で用いる最新を返す。 */
  @GetMapping("/api/v1/projects/{projectId}/summaries/latest")
  public Text latest(@PathVariable String projectId) {
    return new Text(runs.latestSummaryForCli(projectId));
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  @PostMapping("/api/v1/summaries/cli")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> summarize(
      @RequestBody Summary request) {
    if (request == null) throw new IllegalArgumentException("Summary required");
    return accepted(runs.summaryForCli(request.projectId(), request.url()));
  }

  /** Web APIによる操作で用いる画像を返す。 */
  @PostMapping("/api/v1/images/cli")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> image(
      @RequestBody Image request) {
    if (request == null) throw new IllegalArgumentException("Image required");
    return accepted(
        runs.imageForCli(
            request.projectId(),
            request.prompt(),
            request.output(),
            request.model(),
            request.size(),
            Boolean.TRUE.equals(request.raw())));
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
