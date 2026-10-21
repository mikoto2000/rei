package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 記憶Consolidatorサービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MemoryConsolidatorServiceTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 長期記憶の保存と検索で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parseCandidatesParsesJsonArray() {
    MemoryConsolidatorService service = newService();
    String json =
        "[{\"content\":\"繝ｦ繝ｼ繧ｶ繝ｼ縺ｯ譛晏梛\",\"type\":\"USER_PREFERENCE\",\"scope\":\"LONG_TERM\",\"confidence\":0.9}]";

    var result = service.parseCandidates(json);

    assertEquals(1, result.size());
    assertEquals("繝ｦ繝ｼ繧ｶ繝ｼ縺ｯ譛晏梛", result.getFirst().content());
    assertEquals(MemoryType.USER_PREFERENCE, result.getFirst().type());
    assertEquals(MemoryScope.LONG_TERM, result.getFirst().scope());
    assertEquals(0.9d, result.getFirst().confidence());
  }

  /** 入力の解釈候補一覧について、空のを返すことを確認する。 */
  @Test
  void parseCandidatesReturnsEmptyOnInvalidJson() {
    MemoryConsolidatorService service = newService();
    assertTrue(service.parseCandidates("```json\n[]\n```").isEmpty());
  }

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shouldSuggestConsolidationByMessageThreshold() {
    MemoryConsolidatorService service = newService();
    assertTrue(service.shouldSuggestConsolidation(20, 0, 0));
    assertFalse(service.shouldSuggestConsolidation(19, 0, 0));
  }

  /** 長期記憶の保存と検索で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void extractCandidatesThrowsWhenChatClientFails() {
    ChatClient chatClient = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(
            chatClient
                .prompt(Mockito.anyString())
                .advisors(Mockito.any(java.util.function.Consumer.class))
                .call()
                .content())
        .thenThrow(new RuntimeException("boom"));
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("consolidator-fail.db"));
    JdbcClient.create(ds)
        .sql(
            "CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY(type TEXT, content TEXT, timestamp TEXT)")
        .update();
    JdbcClient.create(ds)
        .sql(
            "INSERT INTO SPRING_AI_CHAT_MEMORY(type, content, timestamp) VALUES('USER','hello',datetime('now'))")
        .update();
    var props =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, new MemoryProperties.ExpiryDefaults(30, 365));
    MemoryConsolidatorService service = new MemoryConsolidatorService(chatClient, ds, props);

    assertThrows(IllegalStateException.class, service::extractCandidates);
  }

  /** 長期記憶の保存と検索で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void summarizeThrowsWhenChatClientFails() {
    ChatClient chatClient = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(
            chatClient
                .prompt(Mockito.anyString())
                .advisors(Mockito.any(java.util.function.Consumer.class))
                .call()
                .content())
        .thenThrow(new RuntimeException("boom"));
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("summarize-fail.db"));
    var props =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, new MemoryProperties.ExpiryDefaults(30, 365));
    MemoryConsolidatorService service = new MemoryConsolidatorService(chatClient, ds, props);

    assertThrows(IllegalStateException.class, () -> service.summarize(List.of("a", "b")));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private MemoryConsolidatorService newService() {
    ChatClient chatClient = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("consolidator-test.db"));
    var props =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, new MemoryProperties.ExpiryDefaults(30, 365));
    return new MemoryConsolidatorService(chatClient, ds, props);
  }
}
