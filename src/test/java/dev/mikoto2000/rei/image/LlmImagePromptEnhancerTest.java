package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 言語モデル画像プロンプト内容の補強の結果と状態の契約を検証するテスト。 */
class LlmImagePromptEnhancerTest {

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void generatesImagePromptWithImagePromptFeature() {
    ChatModel chatModel = mock(ChatModel.class);
    LlmModelProvider modelProvider = mock(LlmModelProvider.class);
    ModelHolderService modelHolderService = mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("default-chat-model");
    when(modelProvider.chatOptions(LlmFeature.IMAGE_PROMPT, "default-chat-model"))
        .thenReturn(OpenAiChatOptions.builder().model("prompt-model").maxTokens(8192).build());
    when(modelProvider.chatModel(LlmFeature.IMAGE_PROMPT)).thenReturn(chatModel);
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("  enhanced prompt  ")))));
    LlmImagePromptEnhancer enhancer = new LlmImagePromptEnhancer(modelProvider, modelHolderService);

    String prompt = enhancer.enhance("猫の画像");

    assertThat(prompt).isEqualTo("enhanced prompt");
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(captor.capture());
    assertThat(captor.getValue().getContents()).contains("猫の画像");
    assertThat(captor.getValue().getOptions().getModel()).isEqualTo("prompt-model");
  }
}
