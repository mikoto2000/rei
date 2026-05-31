package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryType;

class MemoryConsolidatorServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void parseCandidatesParsesJsonArray() {
    MemoryConsolidatorService service = newService();
    String json = "[{\"content\":\"繝ｦ繝ｼ繧ｶ繝ｼ縺ｯ譛晏梛\",\"type\":\"USER_PREFERENCE\",\"scope\":\"LONG_TERM\",\"confidence\":0.9}]";

    var result = service.parseCandidates(json);

    assertEquals(1, result.size());
    assertEquals("繝ｦ繝ｼ繧ｶ繝ｼ縺ｯ譛晏梛", result.getFirst().content());
    assertEquals(MemoryType.USER_PREFERENCE, result.getFirst().type());
    assertEquals(MemoryScope.LONG_TERM, result.getFirst().scope());
    assertEquals(0.9d, result.getFirst().confidence());
  }

  @Test
  void parseCandidatesReturnsEmptyOnInvalidJson() {
    MemoryConsolidatorService service = newService();
    assertTrue(service.parseCandidates("```json\n[]\n```").isEmpty());
  }

  @Test
  void shouldSuggestConsolidationByMessageThreshold() {
    MemoryConsolidatorService service = newService();
    assertTrue(service.shouldSuggestConsolidation(20, 0, 0));
    assertFalse(service.shouldSuggestConsolidation(19, 0, 0));
  }

  private MemoryConsolidatorService newService() {
    ChatClient chatClient = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("consolidator-test.db"));
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60,
        new MemoryProperties.ExpiryDefaults(30, 365));
    return new MemoryConsolidatorService(chatClient, ds, props);
  }
}
