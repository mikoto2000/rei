package dev.mikoto2000.rei.memory.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
  private final ObjectMapper objectMapper = new ObjectMapper();

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
    String llmText = null;
    try {
      llmText = chatClient.prompt("次の会話から記憶候補を JSON 配列で返してください:\n" + String.join("\n", messages))
          .call()
          .content();
    } catch (Exception ignored) {
    }
    List<Memory> llmCandidates = parseCandidates(llmText);
    if (!llmCandidates.isEmpty()) {
      return llmCandidates;
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

  List<Memory> parseCandidates(String llmText) {
    if (llmText == null || llmText.isBlank()) {
      return List.of();
    }
    String trimmed = llmText.trim();
    if (!trimmed.startsWith("[")) {
      return List.of();
    }
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(trimmed, new TypeReference<>() {});
      List<Memory> memories = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        String content = stringValue(row.get("content"));
        if (content == null || content.isBlank()) {
          continue;
        }
        MemoryType type = parseType(stringValue(row.get("type")));
        MemoryScope scope = parseScope(stringValue(row.get("scope")));
        double confidence = parseConfidence(row.get("confidence"));
        memories.add(new Memory(null, content, type, scope, MemoryStatus.CANDIDATE, confidence, null, OffsetDateTime.now(),
            OffsetDateTime.now()));
      }
      return memories;
    } catch (Exception ignored) {
      return List.of();
    }
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

  private MemoryType parseType(String value) {
    try {
      return value == null ? MemoryType.KNOWLEDGE : MemoryType.valueOf(value.trim());
    } catch (Exception ignored) {
      return MemoryType.KNOWLEDGE;
    }
  }

  private MemoryScope parseScope(String value) {
    try {
      return value == null ? MemoryScope.SHORT_TERM : MemoryScope.valueOf(value.trim());
    } catch (Exception ignored) {
      return MemoryScope.SHORT_TERM;
    }
  }

  private double parseConfidence(Object value) {
    if (value instanceof Number n) {
      double v = n.doubleValue();
      return Math.max(0.0d, Math.min(1.0d, v));
    }
    return 0.7d;
  }

  private String stringValue(Object value) {
    return value == null ? null : value.toString();
  }
}
