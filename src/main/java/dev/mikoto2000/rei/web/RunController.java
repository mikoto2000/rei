package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 実行をWeb APIの操作として受け付ける。 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rei.web.enabled",
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RunController {
  /** 実行一覧。 */
  private final RunService runs;

  /** 入力一覧。 */
  private final dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs;

  /** 並行有効状態。 */
  private final boolean concurrentEnabled;

  /** Web APIによる操作を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RunController(RunService runs) {
    this(runs, null, false);
  }

  /** 指定された値や利用先を使って、RunControllerを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public RunController(
      RunService runs,
      dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs,
      @org.springframework.beans.factory.annotation.Value(
              "${rei.conversation.concurrent-enabled:false}")
          boolean concurrentEnabled) {
    this.runs = runs;
    this.inputs = inputs;
    this.concurrentEnabled = concurrentEnabled;
  }

  /**
   * Web APIによる操作で受け渡す入力要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param message メッセージ
   */
  public record InputRequest(String projectId, String sessionId, String message)
      implements StrictApiRequest {}

  /** Web APIによる操作で用いる入力を返す。 */
  @PostMapping("/api/v1/runs/{runId}/input")
  public org.springframework.http.ResponseEntity<Void> input(
      @PathVariable String runId, @RequestBody InputRequest request) {
    if (!concurrentEnabled)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT);
    if (request.projectId() == null
        || request.sessionId() == null
        || request.message() == null
        || request.message().isBlank()
        || request.message().length() > 16384)
      throw new IllegalArgumentException("Invalid guidance");
    var run = runs.get(runId);
    // 別のプロジェクトの情報を、この作業の対象として扱わせない。
    if (!request.projectId().equals(run.context().projectId())
        || !request.sessionId().equals(run.context().conversationId()))
      throw new dev.mikoto2000.rei.application.run.RunNotFoundException();
    var stop = runs.cancellationDiagnostic(runId);
    if (stop != null && stop.requestedAt() != null
        || run.status() != dev.mikoto2000.rei.application.run.RunStatus.RUNNING
        || !inputs.offerIntervention(
            request.projectId(), request.sessionId(), runId, request.message()))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT);
    return org.springframework.http.ResponseEntity.accepted().build();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  @GetMapping("/api/v1/runs/{runId}")
  public RunResponse get(@PathVariable String runId) {
    return RunResponse.from(runs.get(runId), runs.cancellationDiagnostic(runId));
  }

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/api/v1/runs/{runId}/cancel")
  public org.springframework.http.ResponseEntity<RunResponse> cancel(@PathVariable String runId) {
    var result = runs.cancel(runId);
    return org.springframework.http.ResponseEntity.status(result.accepted() ? 202 : 200)
        .body(RunResponse.from(result.run(), runs.cancellationDiagnostic(runId)));
  }
}
