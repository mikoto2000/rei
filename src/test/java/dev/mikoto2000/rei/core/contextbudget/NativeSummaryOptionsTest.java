package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.any;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class NativeSummaryOptionsTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void summaryHasItsOwnNativeOptionsAndCannotInheritParentReplayAuthority(boolean localThinking) {
    var models = mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
    var model = mock(ChatModel.class);
    var base =
        OpenAiResponsesChatOptions.builder()
            .model("native")
            .maxOutputTokens(16000)
            .toolContext(Map.of("rei.llm.resolved-endpoint", "summary-endpoint"))
            .build();
    when(models.chatModel("chat")).thenReturn(model);
    when(models.requestOptions("chat", "native", false)).thenReturn(base);
    var beans =
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
            Map.of("models", models));
    var properties = new ContextCompressionProperties();
    if (localThinking) properties.setSummaryEnableThinking(false);
    var owner =
        OpenAiResponsesChatOptions.builder()
            .model("native")
            .toolContext(
                Map.of(
                    "retained-owner-key",
                    "value",
                    "rei.llm.replay-owner",
                    "parent-owner",
                    "rei.llm.resolved-endpoint",
                    "parent-endpoint",
                    "rei.modelMessageExchange",
                    new Object()))
            .build();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          Prompt prompt = invocation.getArgument(0);
          assertThat(prompt.getOptions()).isInstanceOf(OpenAiResponsesChatOptions.class);
          var sent = (OpenAiResponsesChatOptions) prompt.getOptions();
          assertThat(sent.getMaxOutputTokens()).isEqualTo(8292);
          assertThat(sent.getToolContext())
              .containsEntry("retained-owner-key", "value")
              .containsEntry("rei.modelCallPurpose", "context-summary")
              .containsEntry("rei.llm.resolved-endpoint", "summary-endpoint");
          assertThat(sent.getToolContext().get("rei.llm.replay-owner"))
              .asString()
              .startsWith("feature:")
              .isNotEqualTo("parent-owner");
          assertThat(sent.getToolContext()).doesNotContainKey("rei.modelMessageExchange");
          return reactor.core.publisher.Flux.just(
              new ChatResponse(List.of(new Generation(new AssistantMessage("summary")))));
        });
    var compressor =
        new LlmConversationCompressor(
            beans.getBeanProvider(dev.mikoto2000.rei.llm.LlmModelProvider.class), properties);
    if (localThinking) {
      assertThatThrownBy(() ->
              compressor.summarize(
                  "", List.of(new UserMessage("history")), 100, new Prompt("owner", owner)))
          .isInstanceOf(ContextCompressionException.class);
      verify(model, never()).stream(any(Prompt.class));
    } else
      assertThat(
              compressor.summarize(
                  "", List.of(new UserMessage("history")), 100, new Prompt("owner", owner)))
          .isEqualTo("summary");
    verify(models).requestOptions("chat", "native", false);
    assertThat(base.getMaxOutputTokens()).isEqualTo(16000);
    assertThat(owner.getToolContext()).containsEntry("rei.llm.replay-owner", "parent-owner");
  }
}
