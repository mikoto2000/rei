package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.Map;

public record AgentMessage(String id,String role,String content,MessageOrigin origin,Instant createdAt,Map<String,String> metadata) {
  public AgentMessage {metadata=metadata==null?Map.of():Map.copyOf(metadata);}
  public AgentMessage(String id,String role,String content,MessageOrigin origin,Instant createdAt) {
    this(id,role,content,origin,createdAt,Map.of());
  }
}
