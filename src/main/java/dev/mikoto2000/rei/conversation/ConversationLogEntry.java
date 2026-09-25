package dev.mikoto2000.rei.conversation;

import java.time.OffsetDateTime;
import java.util.Map;

public record ConversationLogEntry(String conversationId,String scope,String speaker,OffsetDateTime timestamp,
    String content,long sequence,String source,String sourceId,Map<String,String> metadata) {
  public ConversationLogEntry {metadata=metadata==null?Map.of():Map.copyOf(metadata);}
  public ConversationLogEntry(String conversationId,String scope,String speaker,OffsetDateTime timestamp,String content,long sequence) {
    this(conversationId,scope,speaker,timestamp,content,sequence,null,null,Map.of());
  }
  public ConversationLogEntry(String conversationId,String scope,String speaker,OffsetDateTime timestamp,String content) {
    this(conversationId,scope,speaker,timestamp,content,0);
  }
}
