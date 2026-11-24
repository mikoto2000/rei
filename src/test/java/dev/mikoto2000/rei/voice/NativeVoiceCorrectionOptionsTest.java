package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.llm.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class NativeVoiceCorrectionOptionsTest {
  @ParameterizedTest
  @ValueSource(ints = {150, 10000})
  void correctionUsesNativeBoundsAndAShortenedDeadline(int readMillis) throws Exception {
    var base =
        OpenAiResponsesChatOptions.builder()
            .model("native-correction")
            .maxOutputTokens(73)
            .timeout(Duration.ofMillis(readMillis))
            .build();
    var model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(base);
    when(model.call(any(Prompt.class)))
        .thenAnswer(inv -> {
          Prompt prompt = inv.getArgument(0);
          var sent = (OpenAiResponsesChatOptions) prompt.getOptions();
          assertThat(sent.getMaxOutputTokens()).isEqualTo(73);
          assertThat(sent.getTimeout()).isEqualTo(Duration.ofMillis(Math.min(readMillis, 5000)));
          assertThat(sent.getToolCallbacks()).isEmpty();
          assertThat(sent.getToolChoice()).isNull();
          assertThat(sent.getToolContext().get("rei.llm.replay-owner"))
              .asString()
              .startsWith("feature:");
          return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        });
    var models = mock(LlmModelProvider.class);
    when(models.requestOptions(LlmFeature.VOICE_CORRECTION, null, false)).thenReturn(base);
    var clients = mock(LlmChatClientProvider.class);
    when(clients.chatClient(LlmFeature.VOICE_CORRECTION))
        .thenReturn(ChatClient.builder(model).build());
    var properties = new VoiceCorrectionProperties();
    properties.setMaxTotalTokens(0);
    assertThat(
            new VoiceCorrectionLlm(clients, models, properties)
                .call(
                    "fixture",
                    new VoiceCorrectionContext.Snapshot("project", "Rei", List.of(), List.of())))
        .isEqualTo("{}");
    assertThat(base.getTimeout()).isEqualTo(Duration.ofMillis(readMillis));
  }
}
