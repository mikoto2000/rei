package dev.mikoto2000.rei.web;

import java.net.URI;
import dev.mikoto2000.rei.application.run.ChatSubmitService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatController {
  private final ChatSubmitService chat;
  private dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setAdmissions(dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions){this.admissions=admissions;}
  public ChatController(ChatSubmitService chat) { this.chat = chat; }
  public ResponseEntity<ChatResponse> submit(ChatRequest request) {return submit(request,null);}
  @PostMapping("/api/v1/chat")
  public ResponseEntity<ChatResponse> submit(@RequestBody ChatRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key) {
    var run = chat.submit(request.message(), request.projectId(), request.sessionId(), request.mode(),key);
    return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }
  @GetMapping("/api/v1/chat/receipts/{key}")
  public RunResponse receipt(@PathVariable String key) {
    if(admissions==null)throw new IllegalStateException("Durable admission is unavailable");
    return RunResponse.from(admissions.lookup(key).orElseThrow(dev.mikoto2000.rei.application.run.RunNotFoundException::new));
  }
}
