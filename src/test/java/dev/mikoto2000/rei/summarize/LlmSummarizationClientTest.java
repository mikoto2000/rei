package dev.mikoto2000.rei.summarize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;

class LlmSummarizationClientTest {

  @Test
  void callsFeatureChatModelDirectlyWithExtractedContentPrompt() {
    ChatModel chatModel = Mockito.mock(ChatModel.class);
    LlmModelProvider modelProvider = Mockito.mock(LlmModelProvider.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(modelProvider.chatModel(LlmFeature.WEB_PAGE_SUMMARY)).thenReturn(chatModel);
    when(modelProvider.chatOptions(anyString(), anyString(), Mockito.eq(false))).thenReturn(OpenAiChatOptions.builder().build());
    when(chatModel.call(Mockito.any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("  要約結果  ")))));
    LlmSummarizationClient client = new LlmSummarizationClient(modelProvider, modelHolderService);

    String summary = client.summarize("抽出済み本文");

    assertEquals("要約結果", summary);
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    assertTrue(prompt.getValue().getContents().contains("抽出済み本文"));
    assertTrue(prompt.getValue().getContents().contains("原文に存在しない情報を追加しない"));
    verify(modelProvider).chatModel(LlmFeature.WEB_PAGE_SUMMARY);
  }
}
