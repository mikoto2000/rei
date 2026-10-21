package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

/** 言語モデル関心話題情報の抽出の結果と状態の契約を検証するテスト。 */
class LlmInterestTopicExtractorTest {

  /** 関心の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void extractBuildsPromptAndParsesJsonResponse() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor =
        new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            """
            [
              {
                "topic": "Neovim setup",
                "reason": "User talked about editor setup",
                "searchQuery": "Neovim devcontainer best practices",
                "score": 0.82
              }
            ]
            """)))));

    List<InterestTopicCandidate> topics =
        extractor.extract(
            List.of(
                new ConversationSnippet(
                    "c1",
                    "vim in devcontainer",
                    OffsetDateTime.of(2026, 4, 17, 0, 0, 0, 0, ZoneOffset.UTC)),
                new ConversationSnippet(
                    "c2",
                    "neovim plugins",
                    OffsetDateTime.of(2026, 4, 18, 0, 0, 0, 0, ZoneOffset.UTC))),
            3);

    assertEquals(1, topics.size());
    assertEquals("Neovim setup", topics.getFirst().topic());
    assertEquals("Neovim devcontainer best practices", topics.getFirst().searchQuery());

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    assertTrue(promptCaptor.getValue().getContents().contains("Output must be a JSON array only."));
    assertTrue(promptCaptor.getValue().getContents().contains("vim in devcontainer"));
  }

  /** 関心の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extractWithPastQueriesIncludesPastQueriesInPrompt() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor =
        new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("[]")))));

    List<String> pastQueries = List.of("Neovim devcontainer best practices", "vim plugin 2025");

    extractor.extract(
        List.of(new ConversationSnippet("c1", "neovim", OffsetDateTime.now(ZoneOffset.UTC))),
        3,
        pastQueries);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();

    assertTrue(prompt.contains("Neovim devcontainer best practices"));
    assertTrue(prompt.contains("vim plugin 2025"));
    assertTrue(prompt.contains("Previously used search queries. Avoid duplicates:"));
  }

  /** 関心の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void extractWithEmptyPastQueriesDoesNotAddPastQueriesSection() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor =
        new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("[]")))));

    extractor.extract(
        List.of(new ConversationSnippet("c1", "neovim", OffsetDateTime.now(ZoneOffset.UTC))),
        3,
        List.of());

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();

    assertTrue(!prompt.contains("Previously used search queries. Avoid duplicates:"));
  }

  /** 関心の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void normalizeJsonArray_stripsCodeFence() {
    String normalized =
        LlmInterestTopicExtractor.normalizeJsonArray(
            """
        ```json
        [
          {"topic":"t","reason":"r","searchQuery":"q","score":0.7}
        ]
        ```
        """);
    assertTrue(normalized.startsWith("["));
    assertTrue(normalized.endsWith("]"));
  }

  /** 関心の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void normalizeJsonArray_extractsArrayFromDecoratedText() {
    String normalized =
        LlmInterestTopicExtractor.normalizeJsonArray(
            """
        Here are candidates:
        [
          {"topic":"t","reason":"r","searchQuery":"q","score":0.7}
        ]
        Thanks.
        """);
    assertTrue(normalized.startsWith("["));
    assertTrue(normalized.endsWith("]"));
  }
}
