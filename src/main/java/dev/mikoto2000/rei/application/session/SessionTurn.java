package dev.mikoto2000.rei.application.session;

import java.time.Instant;
import java.util.Map;

public record SessionTurn(String runId,String userMessage,String assistantMessage,Instant createdAt,
    String source,String sourceId,Map<String,String> metadata) {
  public SessionTurn(String runId,String userMessage,String assistantMessage,Instant createdAt) {
    this(runId,userMessage,assistantMessage,createdAt,null,null,Map.of());
  }
}
