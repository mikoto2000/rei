package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.BackgroundRunSubmitService;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** バックグラウンド実行をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class BackgroundRunController {
  /** 実行一覧。 */
  private final BackgroundRunSubmitService runs;

  /** 指定された値や利用先を使って、BackgroundRunControllerを初期化する。 */
  public BackgroundRunController(BackgroundRunSubmitService runs) {
    this.runs = runs;
  }

  /**
   * Web APIによる操作で受け渡す要約要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param url 参照先のURL
   */
  public record SummaryRequest(String projectId, String url) {}

  /**
   * Web APIによる操作で受け渡す画像要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param prompt プロンプト
   * @param size 大きさ
   */
  public record ImageRequest(String projectId, String prompt, String size) {}

  /**
   * Web APIによる操作で受け渡す受付済み実行応答を表す。
   *
   * @param runId 実行の識別子
   */
  public record AcceptedRunResponse(String runId) {}

  /** Web APIによる操作で用いる要約を返す。 */
  @PostMapping("/summaries")
  public ResponseEntity<AcceptedRunResponse> summary(@RequestBody SummaryRequest r) {
    return accepted(runs.summary(r.projectId(), r.url()).runId());
  }

  /** Web APIによる操作で用いる画像を返す。 */
  @PostMapping("/images")
  public ResponseEntity<AcceptedRunResponse> image(@RequestBody ImageRequest r) {
    return accepted(runs.image(r.projectId(), r.prompt(), r.size()).runId());
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private ResponseEntity<AcceptedRunResponse> accepted(String id) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + id))
        .body(new AcceptedRunResponse(id));
  }
}
