package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 復旧をWeb APIの操作として受け付ける。
 *
 * <p>Explicit human recovery operations retain the shared permission and admission boundary.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RecoveryController {
  /** チャット。 */
  private final ChatSubmitService chat;

  /**
   * Web APIによる操作で受け渡す要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param action 操作
   * @param id 識別子
   * @param hash ハッシュ値
   */
  public record Request(String projectId, String sessionId, String action, String id, String hash)
      implements StrictApiRequest {}

  /** 指定された値や利用先を使って、RecoveryControllerを初期化する。 */
  public RecoveryController(ChatSubmitService chat) {
    this.chat = chat;
  }

  /** Web APIによる操作で用いる文書を返す。 */
  @PostMapping("/api/v1/document/recovery")
  public ResponseEntity<ChatResponse> document(
      @RequestBody Request request, @RequestHeader("Idempotency-Key") String key) {
    validate(request, key, "[a-f0-9-]{36}", Set.of("rollback", "clean", "reconcile-single"));
    return submit("/document", request, key);
  }

  /** Web APIによる操作で用いる修復を返す。 */
  @PostMapping("/api/v1/repair/recovery")
  public ResponseEntity<ChatResponse> repair(
      @RequestBody Request request, @RequestHeader("Idempotency-Key") String key) {
    validate(request, key, "[0-9a-fA-F-]{36}", Set.of("apply"));
    return submit("/repair", request, key);
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private static void validate(
      Request request, String key, String identityPattern, Set<String> mutations) {
    if (request == null
        || key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}")
        || request.action() == null
        || request.id() == null
        || !request.id().matches(identityPattern)
        || !("show".equals(request.action()) && request.hash() == null
            || mutations.contains(request.action())
                && request.hash() != null
                && request.hash().matches("[a-f0-9]{64}")))
      throw new IllegalArgumentException(
          "Named recovery action, identity and approval hash required");
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  private ResponseEntity<ChatResponse> submit(String name, Request request, String key) {
    var run =
        chat.submit(
            name
                + " "
                + request.action()
                + " "
                + request.id()
                + (request.hash() == null ? "" : " " + request.hash()),
            request.projectId(),
            request.sessionId(),
            AgentRunContext.Mode.EXCLUSIVE,
            key);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }
}
