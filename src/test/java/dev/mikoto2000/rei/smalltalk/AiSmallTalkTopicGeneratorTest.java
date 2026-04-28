package dev.mikoto2000.rei.smalltalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.ModelHolderService;

class AiSmallTalkTopicGeneratorTest {

  @Test
  void generateBuildsPromptAndReturnsModelResponse() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    AiSmallTalkTopicGenerator generator = new AiSmallTalkTopicGenerator(chatModel, modelHolderService);

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
        java.util.List.of(new Generation(new AssistantMessage("最近気になっている道具やアプリの話をしてみませんか。")))));

    String topic = generator.generate();

    assertEquals("最近気になっている道具やアプリの話をしてみませんか。", topic);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    assertTrue(promptCaptor.getValue().getContents().contains("雑談の話題"));
    assertTrue(promptCaptor.getValue().getContents().contains("会話メモリには保存されない"));
  }
}
