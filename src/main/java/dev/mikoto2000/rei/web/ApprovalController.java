package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 承認をWeb APIの操作として受け付ける。
 *
 * <p>Human-facing API only; deciding does not dispatch or execute the Tool.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/approvals")
public class ApprovalController {
  /** 保存と検索の窓口。 */
  private final ToolApprovalRepository repository;

  /** 継続入力一覧。 */
  private dev.mikoto2000.rei.core.policy.ApprovalContinuationService continuations;

  /** Web APIによる操作で使用する継続入力一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setContinuations(
      dev.mikoto2000.rei.core.policy.ApprovalContinuationService continuations) {
    this.continuations = continuations;
  }

  /** 指定された値や利用先を使って、ApprovalControllerを初期化する。 */
  public ApprovalController(ToolApprovalRepository repository) {
    this.repository = repository;
  }

  /**
   * Web APIによる操作で受け渡す表示内容を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param runId 実行の識別子
   * @param tool ツール
   * @param argumentsPreview 引数事前確認
   * @param status 状態
   * @param expiresAt 有効期限
   * @param continuationState 継続入力状態
   */
  public record View(
      String id,
      String projectId,
      String sessionId,
      String runId,
      String tool,
      String argumentsPreview,
      String status,
      java.time.Instant expiresAt,
      String continuationState) {}

  /** Web APIによる操作で用いる表示内容を返す。 */
  private View view(ToolApprovalRepository.Request request) {
    if (request == null) return null;
    return new View(
        request.id(),
        request.projectId(),
        request.sessionId(),
        request.runId(),
        request.tool(),
        request.argumentsPreview(),
        request.status(),
        request.expiresAt(),
        continuations == null
            ? "UNAVAILABLE"
            : continuations.state(request.id()).orElse("UNAVAILABLE"));
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public List<View> list(@PathVariable String projectId) {
    return (continuations == null ? repository.list(projectId) : continuations.list(projectId))
        .stream().map(this::view).toList();
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public View show(@PathVariable String projectId, @PathVariable String id) {
    return view(repository.get(projectId, id));
  }

  /**
   * Web APIによる操作で受け渡す判断結果を表す。
   *
   * @param approved 承認済み
   * @param continueRun continue実行
   */
  public record Decision(Boolean approved, Boolean continueRun) {
    /** Web APIによる操作を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Decision(Boolean approved) {
      this(approved, false);
    }
  }

  /** Web APIによる操作で用いる判断を返す。 */
  @PostMapping("/{id}/decision")
  public View decide(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Decision decision) {
    if (decision.approved() == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "approved is required");
    if (Boolean.TRUE.equals(decision.continueRun()) && continuations == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "Approval continuation unavailable");
    return view(
        continuations == null
            ? repository.decide(projectId, id, decision.approved())
            : continuations.decide(
                projectId, id, decision.approved(), Boolean.TRUE.equals(decision.continueRun())));
  }

  /** Web APIによる操作で用いる継続入力を返す。 */
  @GetMapping("/{id}/continuation")
  public java.util.Map<String, String> continuation(
      @PathVariable String projectId, @PathVariable String id) {
    var request = repository.get(projectId, id);
    return java.util.Map.of(
        "approvalId",
        request.id(),
        "runId",
        request.runId(),
        "state",
        continuations == null ? "UNAVAILABLE" : continuations.state(id).orElse("UNAVAILABLE"));
  }
}
