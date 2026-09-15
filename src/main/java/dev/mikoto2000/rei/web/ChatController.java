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
  public ChatController(ChatSubmitService chat) { this.chat = chat; }
  @PostMapping("/api/v1/chat")
  public ResponseEntity<ChatResponse> submit(@RequestBody ChatRequest request) {
    var run = chat.submit(request.message(), request.projectId(), request.sessionId());
    return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }
}
