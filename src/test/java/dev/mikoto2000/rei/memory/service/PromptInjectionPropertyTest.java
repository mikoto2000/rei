package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** プロンプト不正な指示の混入Propertyの結果と状態の契約を検証するテスト。 */
@net.jqwik.api.Tag("integration")
class PromptInjectionPropertyTest {

  // Feature: ai-memory-consolidation, Property 9: プロンプト注入の件数上限
  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Property(tries = 100)
  void selectedPromptMemoriesAreCapped(@ForAll @IntRange(min = 0, max = 20) int size)
      throws Exception {
    MemoryConsolidatorService service = newService(3);
    List<Memory> memories = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      memories.add(
          new Memory(
              "id" + i,
              "content" + i,
              MemoryType.KNOWLEDGE,
              MemoryScope.SHORT_TERM,
              MemoryStatus.ACTIVE,
              i / 100.0d,
              null,
              OffsetDateTime.now(),
              OffsetDateTime.now()));
    }

    List<Memory> selected = service.selectPromptMemories(memories);

    assertTrue(selected.size() <= 3);
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private MemoryConsolidatorService newService(int injectedLimit) throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
    var tempDir = Files.createTempDirectory("rei-prompt-injection-pbt-");
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("prompt-injection.db"));
    var props =
        new MemoryProperties(
            true,
            20,
            80,
            10,
            injectedLimit,
            2000,
            60,
            new MemoryProperties.ExpiryDefaults(30, 365));
    return new MemoryConsolidatorService(chatClient, ds, props);
  }
}
