package dev.mikoto2000.rei.memory.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;

@Service
public class MemoryConsolidatorService {

  private final ChatClient chatClient;
  private final JdbcClient jdbcClient;
  private final MemoryProperties memoryProperties;

  public MemoryConsolidatorService(ChatClient chatClient,
      @Qualifier("dataSource") javax.sql.DataSource dataSource,
      MemoryProperties memoryProperties) {
    this.chatClient = chatClient;
    this.jdbcClient = JdbcClient.create(dataSource);
    this.memoryProperties = memoryProperties;
  }

  public List<Memory> extractCandidates() {
    List<String> messages = jdbcClient.sql("""
        SELECT content FROM SPRING_AI_CHAT_MEMORY
        WHERE type IN ('USER', 'ASSISTANT')
        ORDER BY timestamp DESC
        LIMIT 200
        """)
        .query((rs, rowNum) -> rs.getString("content"))
        .list();
    if (messages.isEmpty()) {
      return List.of();
    }
    List<Memory> candidates = new ArrayList<>();
    int max = Math.min(messages.size(), 5);
    for (int i = 0; i < max; i++) {
      String content = messages.get(i);
      if (content == null || content.isBlank()) {
        continue;
      }
      candidates.add(new Memory(
          null,
          content.length() > 400 ? content.substring(0, 400) : content,
          MemoryType.KNOWLEDGE,
          MemoryScope.SHORT_TERM,
          MemoryStatus.CANDIDATE,
          0.7d,
          null,
          OffsetDateTime.now(),
          OffsetDateTime.now()));
    }
    return candidates;
  }

  public String summarize(List<String> conversation) {
    if (conversation == null || conversation.isEmpty()) {
      return "";
    }
    String prompt = String.join("\n", conversation);
    String summary;
    try {
      summary = chatClient.prompt("以下を要約してください:\n" + prompt).call().content();
    } catch (Exception e) {
      summary = prompt;
    }
    if (summary == null) {
      summary = "";
    }
    int max = memoryProperties.summarizeMaxLength();
    return summary.length() <= max ? summary : summary.substring(0, max);
  }

  public boolean shouldSuggestConsolidation(int messageCount, int contextLength, int contextLimit) {
    if (!memoryProperties.enabled()) {
      return false;
    }
    if (messageCount >= memoryProperties.autoTriggerMessageThreshold()) {
      return true;
    }
    if (contextLimit <= 0) {
      return false;
    }
    int usedPercent = (int) ((contextLength * 100.0d) / contextLimit);
    return usedPercent >= memoryProperties.autoTriggerContextPercent();
  }

  public boolean shouldSuggestConsolidationNow() {
    Integer messageCount = jdbcClient.sql("SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY")
        .query(Integer.class)
        .single();
    return shouldSuggestConsolidation(messageCount == null ? 0 : messageCount, 0, 0);
  }
}
