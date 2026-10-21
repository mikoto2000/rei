package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 会話の実行要求を受け付け、後から状態を照会するための識別子を返す。 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rei.web.enabled",
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatController {
  /** チャット。 */
  private final ChatSubmitService chat;

  /** 受付台帳。 */
  private dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions;

  /** Web APIによる操作で使用する受付台帳を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setAdmissions(dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions) {
    this.admissions = admissions;
  }

  /** 指定された値や利用先を使って、ChatControllerを初期化する。 */
  public ChatController(ChatSubmitService chat) {
    this.chat = chat;
  }

  /** 会話の実行を受け付け、実行状態を照会する識別子と参照先を返す。 */
  public ResponseEntity<ChatResponse> submit(ChatRequest request) {
    return submit(request, null);
  }

  /** 会話の実行を受け付け、実行状態を照会する識別子と参照先を返す。 */
  public ResponseEntity<ChatResponse> submit(ChatRequest request, String key) {
    return submit(request, key, null);
  }

  /** 会話の実行を受け付け、実行状態を照会する識別子と参照先を返す。 */
  @PostMapping("/api/v1/chat")
  public ResponseEntity<ChatResponse> submit(
      @RequestBody ChatRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestHeader(value = "X-Rei-Client-Id", required = false) String clientId) {
    var run =
        clientId == null
            ? chat.submit(
                request.message(), request.projectId(), request.sessionId(), request.mode(), key)
            : chat.submit(
                request.message(),
                request.projectId(),
                request.sessionId(),
                request.mode(),
                key,
                clientId);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }

  /** 重複受付防止キーから、受け付け済みの実行を照会する。 */
  @GetMapping("/api/v1/chat/receipts/{key}")
  public RunResponse receipt(@PathVariable String key) {
    if (admissions == null) throw new IllegalStateException("Durable admission is unavailable");
    return RunResponse.from(
        admissions
            .lookup(key)
            .orElseThrow(dev.mikoto2000.rei.application.run.RunNotFoundException::new));
  }
}
