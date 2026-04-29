package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import dev.mikoto2000.rei.core.service.ModelHolderService;
import tools.jackson.databind.json.JsonMapper;

class LlmInterestTopicExtractorTest {

  @Test
  void extractBuildsPromptAndParsesJsonResponse() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor = new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
        new Generation(new AssistantMessage("""
            [
              {
                "topic": "Neovim 開発環境",
                "reason": "devcontainer と vim 関連の話題が繰り返し出ているため",
                "searchQuery": "Neovim devcontainer best practices",
                "score": 0.82
              }
            ]
            """)))));

    List<InterestTopicCandidate> topics = extractor.extract(List.of(
        new ConversationSnippet("c1", "vim の devcontainer を改善したい", OffsetDateTime.of(2026, 4, 17, 0, 0, 0, 0, ZoneOffset.UTC)),
        new ConversationSnippet("c2", "neovim plugin の最近動向が気になる", OffsetDateTime.of(2026, 4, 18, 0, 0, 0, 0, ZoneOffset.UTC))),
        3);

    assertEquals(1, topics.size());
    assertEquals("Neovim 開発環境", topics.getFirst().topic());
    assertEquals("Neovim devcontainer best practices", topics.getFirst().searchQuery());

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    assertTrue(promptCaptor.getValue().getContents().contains("JSON 配列のみ"));
    assertTrue(promptCaptor.getValue().getContents().contains("vim の devcontainer を改善したい"));
  }

  @Test
  void extractWithPastQueriesIncludesPastQueriesInPrompt() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor = new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
        new Generation(new AssistantMessage("[]")))));

    List<String> pastQueries = List.of("Neovim devcontainer best practices", "vim plugin 2025");

    extractor.extract(
        List.of(new ConversationSnippet("c1", "neovim の話", OffsetDateTime.now(ZoneOffset.UTC))),
        3,
        pastQueries);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();

    // 過去クエリが両方プロンプトに含まれること
    assertTrue(prompt.contains("Neovim devcontainer best practices"),
        "過去クエリ 1 がプロンプトに含まれるべき");
    assertTrue(prompt.contains("vim plugin 2025"),
        "過去クエリ 2 がプロンプトに含まれるべき");
    // 重複回避の指示が含まれること
    assertTrue(prompt.contains("重複") || prompt.contains("新しい角度"),
        "重複回避の指示がプロンプトに含まれるべき");
  }

  @Test
  void extractWithEmptyPastQueriesDoesNotAddPastQueriesSection() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor = new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
        new Generation(new AssistantMessage("[]")))));

    extractor.extract(
        List.of(new ConversationSnippet("c1", "neovim の話", OffsetDateTime.now(ZoneOffset.UTC))),
        3,
        List.of()); // 空の過去クエリ

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();

    // 過去クエリセクションが含まれないこと
    assertTrue(!prompt.contains("過去に使用した検索クエリ"),
        "空の過去クエリの場合、過去クエリセクションはプロンプトに含まれないべき");
  }
}
