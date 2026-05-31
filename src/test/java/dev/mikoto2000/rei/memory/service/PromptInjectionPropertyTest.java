package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class PromptInjectionPropertyTest {

  // Feature: ai-memory-consolidation, Property 9: プロンプト注入の件数上限
  @Property(tries = 100)
  void selectedPromptMemoriesAreCapped(@ForAll @IntRange(min = 0, max = 20) int size) throws Exception {
    MemoryConsolidatorService service = newService(3);
    List<Memory> memories = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      memories.add(new Memory("id" + i, "content" + i, MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
          MemoryStatus.ACTIVE, i / 100.0d, null, OffsetDateTime.now(), OffsetDateTime.now()));
    }

    List<Memory> selected = service.selectPromptMemories(memories);

    assertTrue(selected.size() <= 3);
  }

  private MemoryConsolidatorService newService(int injectedLimit) throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
    var tempDir = Files.createTempDirectory("rei-prompt-injection-pbt-");
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("prompt-injection.db"));
    var props = new MemoryProperties(true, 20, 80, 10, injectedLimit, 2000, 60,
        new MemoryProperties.ExpiryDefaults(30, 365));
    return new MemoryConsolidatorService(chatClient, ds, props);
  }
}
