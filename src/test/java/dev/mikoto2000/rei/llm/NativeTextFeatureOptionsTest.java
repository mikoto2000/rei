package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.any;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class NativeTextFeatureOptionsTest {
  @ParameterizedTest
  @ValueSource(strings = {"image-prompt", "web-page-summary"})
  void forwardsTheNativeSnapshotToTheExistingFeature(String feature) {
    var models = mock(LlmModelProvider.class);
    var holder = mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
    when(holder.get()).thenReturn("selected");
    var model = mock(ChatModel.class);
    var nativeOptions =
        OpenAiResponsesChatOptions.builder().model("native").maxOutputTokens(321).build();
    when(models.requestOptions(feature, "selected", false)).thenReturn(nativeOptions);
    when(models.chatModel(feature)).thenReturn(model);
    var owners = new java.util.ArrayList<Object>();
    when(model.call(any(Prompt.class)))
        .thenAnswer(call -> {
          Prompt prompt = call.getArgument(0);
          assertThat(prompt.getOptions()).isInstanceOf(OpenAiResponsesChatOptions.class);
          var sent = (OpenAiResponsesChatOptions) prompt.getOptions();
          assertThat(sent.getMaxOutputTokens()).isEqualTo(321);
          assertThat(sent.getModel()).isEqualTo("native");
          assertThat(sent.getToolContext().get("rei.llm.replay-owner"))
              .asString()
              .startsWith("feature:");
          owners.add(sent.getToolContext().get("rei.llm.replay-owner"));
          assertThat(sent.getToolCallbacks()).isEmpty();
          assertThat(nativeOptions.getToolContext()).isNull();
          return new ChatResponse(List.of(new Generation(new AssistantMessage("result"))));
        });
    for (int request = 0; request < 2; request++) {
      if (feature.equals("image-prompt"))
        assertThat(
                new dev.mikoto2000.rei.image.LlmImagePromptEnhancer(models, holder)
                    .enhance("input"))
            .isEqualTo("result");
      else
        assertThat(
                new dev.mikoto2000.rei.summarize.LlmSummarizationClient(models, holder)
                    .summarize("input"))
            .isEqualTo("result");
    }
    assertThat(owners).hasSize(2).doesNotHaveDuplicates();
    verify(models, never()).chatOptions(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "rei.llm.replay-owner",
        "rei.modelMessageOrigin",
        "rei.modelMessageExchange",
        "rei.modelMessage.historyGrant"
      })
  void oneShotFeaturesRejectInheritedConversationReplayAuthority(String key) {
    var models = mock(LlmModelProvider.class);
    var holder = mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
    when(holder.get()).thenReturn("selected");
    var nativeOptions =
        OpenAiResponsesChatOptions.builder()
            .model("native")
            .maxOutputTokens(321)
            .toolContext(java.util.Map.of(key, "foreign-authority"))
            .build();
    when(models.requestOptions("image-prompt", "selected", false)).thenReturn(nativeOptions);
    var model = mock(ChatModel.class);
    when(models.chatModel("image-prompt")).thenReturn(model);
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new dev.mikoto2000.rei.image.LlmImagePromptEnhancer(models, holder).enhance("input"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replay authority");
    verifyNoInteractions(model);
    assertThat(nativeOptions.getToolContext()).containsEntry(key, "foreign-authority");
  }
}
