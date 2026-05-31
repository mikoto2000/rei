package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.file.Files;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotBlank;

class MemoryConsolidatorServicePropertyTest {

  // Feature: ai-memory-consolidation, Property 13: 自動トリガー判定の閾値
  @Property(tries = 100)
  void shouldSuggestByMessageThreshold(@ForAll @IntRange(min = 0, max = 200) int messageCount,
      @ForAll @IntRange(min = 1, max = 200) int threshold) throws Exception {
    MemoryConsolidatorService service = newService(threshold, 80, 2000, "ok");

    boolean suggested = service.shouldSuggestConsolidation(messageCount, 0, 0);

    assertEquals(messageCount >= threshold, suggested);
  }

  // Feature: ai-memory-consolidation, Property 13: 自動トリガー判定の閾値
  @Property(tries = 100)
  void shouldSuggestByContextUsage(@ForAll @IntRange(min = 0, max = 20000) int contextLength,
      @ForAll @IntRange(min = 1, max = 20000) int contextLimit,
      @ForAll @IntRange(min = 1, max = 99) int percent) throws Exception {
    MemoryConsolidatorService service = newService(999, percent, 2000, "ok");

    boolean suggested = service.shouldSuggestConsolidation(0, contextLength, contextLimit);
    int usedPercent = (int) ((contextLength * 100.0d) / contextLimit);

    assertEquals(usedPercent >= percent, suggested);
  }

  // Feature: ai-memory-consolidation, Property 16: 要約の長さ制約
  @Property(tries = 50)
  void summarizeNeverExceedsConfiguredLength(@ForAll @AlphaChars @NotBlank String conversation,
      @ForAll @IntRange(min = 1, max = 4000) int maxLength) throws Exception {
    String longSummary = "x".repeat(Math.max(maxLength + 200, 500));
    MemoryConsolidatorService service = newService(20, 80, maxLength, longSummary);

    String summary = service.summarize(java.util.List.of(conversation));

    assertTrue(summary.length() <= maxLength);
  }

  // Feature: ai-memory-consolidation, Property 1: 保存候補の不変条件
  @Property(tries = 100)
  void parsedCandidatesHaveExpectedInvariants(@ForAll @AlphaChars @NotBlank String content,
      @ForAll @DoubleRange(min = -5.0, max = 5.0) double confidence) throws Exception {
    MemoryConsolidatorService service = newService(20, 80, 2000, "ok");
    String json = "[{\"content\":\"" + content + "\",\"type\":\"KNOWLEDGE\",\"scope\":\"SHORT_TERM\",\"confidence\":" + confidence + "}]";

    var parsed = service.parseCandidates(json);

    assertFalse(parsed.isEmpty());
    var memory = parsed.getFirst();
    assertEquals(MemoryStatus.CANDIDATE, memory.status());
    assertTrue(memory.confidence() >= 0.0d && memory.confidence() <= 1.0d);
  }

  private MemoryConsolidatorService newService(int threshold, int percent, int summarizeMaxLength, String llmSummary)
      throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
    when(chatClient.prompt(anyString()).call().content()).thenReturn(llmSummary);

    var tempDir = Files.createTempDirectory("rei-memory-consolidator-pbt-");
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("consolidator-pbt.db"));
    var props = new MemoryProperties(true, threshold, percent, 10, 3, summarizeMaxLength, 60,
        new MemoryProperties.ExpiryDefaults(30, 365));
    return new MemoryConsolidatorService(chatClient, ds, props);
  }
}
