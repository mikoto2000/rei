package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.dot.DotDelegationService;
import dev.mikoto2000.rei.dot.DotResumeService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 状態表示をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/dot-requests")
public final class DotController {
  /** サービス。 */
  private final DotDelegationService service;

  /** 待機していた作業を再開する窓口。 */
  private final DotResumeService resumes;

  /** 指定された値や利用先を使って、DotControllerを初期化する。 */
  public DotController(DotDelegationService service, DotResumeService resumes) {
    this.service = service;
    this.resumes = resumes;
  }

  /**
   * Web APIによる操作で受け渡す作成要求を表す。
   *
   * @param request_id 要求の識別子
   * @param session_id 会話の識別子
   * @param category 分類
   * @param instruction 指示
   * @param context_summary 文脈要約
   * @param expected_output 期待する内容出力
   * @param constraints 制約一覧
   * @param acceptance_criteria 受け入れ条件判断基準一覧
   * @param timeout タイムアウト
   */
  public record CreateRequest(
      String request_id,
      String session_id,
      String category,
      String instruction,
      String context_summary,
      String expected_output,
      List<String> constraints,
      List<String> acceptance_criteria,
      Object timeout)
      implements StrictApiRequest {
    /** Web APIによる操作で用いる通知内容を返す。 */
    Map<String, Object> payload() {
      var a = new LinkedHashMap<String, Object>();
      if (request_id != null) a.put("request_id", request_id);
      if (category != null) a.put("category", category);
      a.put("instruction", instruction);
      if (context_summary != null) a.put("context_summary", context_summary);
      if (expected_output != null) a.put("expected_output", expected_output);
      if (constraints != null) a.put("constraints", constraints);
      if (acceptance_criteria != null) a.put("acceptance_criteria", acceptance_criteria);
      if (timeout != null) a.put("timeout", timeout);
      return a;
    }
  }

  /** 指定された内容から新しい対象を作成する。 */
  @PostMapping
  public Map<String, Object> create(
      @PathVariable String projectId, @RequestBody CreateRequest request) {
    return service.createHuman(projectId, request.session_id(), request.payload());
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public List<Map<String, Object>> list(@PathVariable String projectId) {
    return service.list(projectId);
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String projectId, @PathVariable String id) {
    return resumes.status(projectId, id);
  }

  /** Web APIによる操作で用いる結果を返す。 */
  @GetMapping("/{id}/result")
  public Map<String, Object> result(@PathVariable String projectId, @PathVariable String id) {
    return service.result(projectId, id);
  }

  /**
   * Web APIによる操作で受け渡すレビュー要求を表す。
   *
   * @param confirmed 確認済み
   * @param status 状態
   * @param note 補足
   * @param satisfied 条件を満たしたかどうか
   */
  public record ReviewRequest(Boolean confirmed, String status, String note, Object satisfied)
      implements StrictApiRequest {
    /** Web APIによる操作で用いる通知内容を返す。 */
    Map<String, Object> payload() {
      var out = new LinkedHashMap<String, Object>();
      out.put("confirmed", confirmed);
      out.put("status", status);
      out.put("note", note);
      if (satisfied != null) out.put("satisfied", satisfied);
      return out;
    }
  }

  /**
   * Web APIによる操作で受け渡す再開要求を表す。
   *
   * @param confirmed 確認済み
   */
  public record ResumeRequest(Boolean confirmed) implements StrictApiRequest {}

  /** 要求された条件を満たすことを確認する。 */
  @PostMapping("/{id}/verify")
  public Map<String, Object> verify(
      @PathVariable String projectId, @PathVariable String id, @RequestBody ReviewRequest request) {
    return resumes.verify(projectId, id, request.payload());
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  @PostMapping("/{id}/resume")
  public Map<String, Object> resume(
      @PathVariable String projectId, @PathVariable String id, @RequestBody ResumeRequest request) {
    return resumes.resume(
        projectId, id, Collections.singletonMap("confirmed", request.confirmed()));
  }

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable String projectId, @PathVariable String id) {
    return service.cancel(projectId, id);
  }
}
