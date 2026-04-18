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
}
