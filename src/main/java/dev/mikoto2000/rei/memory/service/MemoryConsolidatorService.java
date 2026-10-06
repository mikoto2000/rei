package dev.mikoto2000.rei.memory.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.configuration.MemoryConsolidationProperties;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;

@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(MemoryConsolidationProperties.class)
public class MemoryConsolidatorService {

  private final LlmChatClientProvider chatClientProvider;
  private final JdbcClient jdbcClient;
  private final MemoryProperties memoryProperties;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private MemoryConsolidationProperties consolidation=new MemoryConsolidationProperties(0,0);
  @Autowired
  public void setConsolidationProperties(MemoryConsolidationProperties properties){this.consolidation=properties;}
  public record Summary(boolean hasCandidates,String summary) {}
  public Summary summarizeCandidates() {
    var budget=new ConsolidationModelBudget(consolidation);
    var candidates=extractCandidates(budget);
    return candidates.isEmpty()?new Summary(false,""):new Summary(true,summarize(candidates.stream().map(Memory::content).toList(),budget));
  }

  public MemoryConsolidatorService(ChatClient chatClient,
      @Qualifier("dataSource") javax.sql.DataSource dataSource,
      MemoryProperties memoryProperties) {
    this(new dev.mikoto2000.rei.llm.FixedLlmChatClientProvider(chatClient), dataSource,
        memoryProperties);
  }

  @Autowired
  public MemoryConsolidatorService(LlmChatClientProvider chatClientProvider,
      @Qualifier("dataSource") javax.sql.DataSource dataSource,
      MemoryProperties memoryProperties) {
    this.chatClientProvider = chatClientProvider;
    this.jdbcClient = JdbcClient.create(dataSource);
    this.memoryProperties = memoryProperties;
  }

  public List<Memory> extractCandidates() {
    return extractCandidates(new ConsolidationModelBudget(consolidation));
  }
  private List<Memory> extractCandidates(ModelCallBudget budget) {
    RunCancellation.propagate(null);
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

    String llmText;
    try {
      llmText = call("次の会話から保存候補をJSON配列で返してください:\n" + String.join("\n", messages),"memory-extract",budget);
    } catch (Exception e) {
      RunCancellation.propagate(e);if(e instanceof ExecutionStoppedException stopped)throw stopped;
      throw new IllegalStateException("LLM での候補抽出に失敗しました", e);
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
      List<Map<String, Object>> rows = objectMapper.readValue(trimmed, new TypeReference<>() {
      });
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
    return summarize(conversation,new ConsolidationModelBudget(consolidation));
  }
  private String summarize(List<String> conversation,ModelCallBudget budget) {
    RunCancellation.propagate(null);
    if (conversation == null || conversation.isEmpty()) {
      return "";
    }
    String prompt = String.join("\n", conversation);
    String summary;
    try {
      summary = call("会話を要約してください:\n" + prompt,"memory-summarize",budget);
    } catch (Exception e) {
      RunCancellation.propagate(e);if(e instanceof ExecutionStoppedException stopped)throw stopped;
      throw new IllegalStateException("LLM での要約に失敗しました", e);
    }
    if (summary == null) {
      summary = "";
    }
    int max = memoryProperties.summarizeMaxLength();
    return summary.length() <= max ? summary : summary.substring(0, max);
  }

  private String call(String prompt,String conversation,ModelCallBudget budget) {
    budget.run();boolean invoked=false,reported=false;
    try {
      var request=chatClientProvider.chatClient(LlmFeature.MEMORY).prompt(prompt)
          .advisors(advisor->advisor.param(ChatMemory.CONVERSATION_ID,ConversationIds.tool(conversation)));
      if(!budget.tokenLimitEnabled()) {
        invoked=true;var text=request.call().content();RunCancellation.propagate(null);return text;
      }
      invoked=true;var response=request.call().chatResponse();RunCancellation.propagate(null);
      var usage=response==null||response.getMetadata()==null?null:response.getMetadata().getUsage();
      Integer tokens=usage==null?null:usage.getTotalTokens();reported=true;
      budget.recordTotalTokens(tokens);
      return response==null||response.getResult()==null||response.getResult().getOutput()==null?null:response.getResult().getOutput().getText();
    }catch(RuntimeException error) {
      RunCancellation.propagate(error);
      if(error instanceof ExecutionStoppedException)throw error;
      if(invoked&&!reported&&budget.tokenLimitEnabled())budget.recordTotalTokens(null);
      throw error;
    }
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

  public List<Memory> selectPromptMemories(List<Memory> memories) {
    if (memories == null || memories.isEmpty()) {
      return List.of();
    }
    int limit = Math.max(1, memoryProperties.searchMaxInjected());
    return memories.stream()
        .filter(m -> m != null && m.status() == MemoryStatus.ACTIVE)
        .sorted(Comparator.comparingDouble(Memory::confidence).reversed())
        .limit(limit)
        .toList();
  }

  public int conflictTimeoutSeconds() {
    return memoryProperties.conflictTimeoutSeconds();
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
