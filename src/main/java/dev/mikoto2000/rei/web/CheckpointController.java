package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.checkpoint.CheckpointReconciler;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 再開用の記録をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/checkpoints")
public class CheckpointController {
  /** サービス。 */
  private final PersistentCheckpointService service;

  /** 指定された値や利用先を使って、CheckpointControllerを初期化する。 */
  public CheckpointController(PersistentCheckpointService service) {
    this.service = service;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public List<PersistentCheckpoint> list(@PathVariable String projectId) {
    return service.list(projectId);
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {
    /** 受け取った情報からTextを作成する。 */
    public Text {
      text = dev.mikoto2000.rei.event.CredentialRedactor.redact(text);
    }
  }

  /** Web APIによる操作で用いるレポートを返す。 */
  @GetMapping("/report")
  public Text report(@PathVariable String projectId) {
    return new Text(String.valueOf(service.list(projectId)));
  }

  /** Web APIによる操作で用いるレポート一覧を返す。 */
  @GetMapping("/report/list")
  public Text reportList(@PathVariable String projectId) {
    return new Text(
        String.valueOf(
            service.list(projectId).stream()
                .map(
                    state ->
                        state.taskId()
                            + " ["
                            + state.status()
                            + "] "
                            + PersistentCheckpointService.bounded(state.request(), 160))
                .toList()));
  }

  /** Web APIによる操作で用いるレポート表示を返す。 */
  @GetMapping("/{taskId}/report")
  public Text reportShow(@PathVariable String projectId, @PathVariable String taskId) {
    return new Text(service.get(projectId, taskId) + "\n" + service.inspect(projectId, taskId));
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{taskId}")
  public PersistentCheckpoint show(@PathVariable String projectId, @PathVariable String taskId) {
    return service.get(projectId, taskId);
  }

  /** 対象の状態や内容を調べ、呼び出し元が確認できる情報として返す。 */
  @GetMapping("/{taskId}/reconciliation")
  public CheckpointReconciler.Result inspect(
      @PathVariable String projectId, @PathVariable String taskId) {
    return service.inspect(projectId, taskId);
  }

  /**
   * Web APIによる操作で受け渡す保存要求を表す。
   *
   * @param sessionId 会話の識別子
   */
  public record SaveRequest(String sessionId) {}

  /** 後から参照できるよう、指定された情報を保存する。 */
  @PostMapping
  public PersistentCheckpoint save(
      @PathVariable String projectId, @RequestBody SaveRequest request) {
    return service.saveCurrent(projectId, request.sessionId());
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  @PostMapping("/{taskId}/resume")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public PersistentCheckpointService.ResumeResult resume(
      @PathVariable String projectId, @PathVariable String taskId) {
    return service.resumeForUser(projectId, taskId, AgentRunContext.RequestSource.WEB);
  }

  /** Web APIによる操作で用いるabandonを返す。 */
  @PostMapping("/{taskId}/abandon")
  public PersistentCheckpoint abandon(@PathVariable String projectId, @PathVariable String taskId) {
    return service.abandon(projectId, taskId);
  }
}
