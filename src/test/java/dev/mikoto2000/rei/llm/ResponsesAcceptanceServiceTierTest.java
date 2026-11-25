package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

@Tag("integration")
class ResponsesAcceptanceServiceTierTest {
  @TempDir Path root;
  private final ChatModel delegate = org.mockito.Mockito.mock(ChatModel.class);

  private ResponsesAcceptanceBudget budget() {
    return new ResponsesAcceptanceBudget(
        root.resolve("tier.jsonl"),
        "tier-fixture",
        new ResponsesAcceptanceBudget.Limits(
            5, 1000000, 300000, Duration.ofHours(3), 20000000, 10, 30),
        Clock.systemUTC());
  }

  private ChatResponse response() {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .parts(
                        List.of(
                            new org.springframework.ai.chat.messages.part.TextPart(
                                "done", null, Map.of())))
                    .properties(
                        Map.of(
                            dev.mikoto2000.rei.modelmessage.ModelTokenUsage.METADATA_KEY,
                            new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(
                                40L, 20L, 60L, 0L, 10L)))
                    .build())));
  }

  @Test
  void operationalLowEffortPreservesContextForSyncAndStreamWithoutAddingALocalTier() {
    var options =
        OpenAiResponsesChatOptions.builder()
            .model("fixture")
            .maxOutputTokens(2048)
            .toolContext(Map.of("owned-context", "retained"))
            .build();
    java.util.function.Consumer<Prompt> check =
        prompt -> {
          var actual = (OpenAiResponsesChatOptions) prompt.getOptions();
          assertThat(actual.getReasoningEffort()).isEqualTo("low");
          assertThat(actual.getServiceTier()).isNull();
          assertThat(actual.getToolContext()).containsEntry("owned-context", "retained");
        };
    when(delegate.call(any(Prompt.class)))
        .thenAnswer(invocation -> {
          check.accept(invocation.getArgument(0));
          return response();
        });
    when(delegate.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          check.accept(invocation.getArgument(0));
          return reactor.core.publisher.Flux.just(response());
        });
    var budget = budget();
    var model = new ResponsesAcceptanceMeter(delegate, budget, false, true);
    model.call(new Prompt("synthetic", options));
    model.stream(new Prompt("synthetic", options)).collectList().block();
    assertThat(options.getReasoningEffort()).isNull();
    assertThat(budget.snapshot().completed()).isEqualTo(2);
  }

  @Test
  void operationalEffortOverrideIsRejectedBeforeQuotaAndTransport() {
    var budget = budget();
    var options =
        OpenAiResponsesChatOptions.builder()
            .model("fixture")
            .maxOutputTokens(2048)
            .reasoningEffort("medium")
            .build();
    assertThatThrownBy(() ->
            new ResponsesAcceptanceMeter(delegate, budget, true, true)
                .call(new Prompt("synthetic", options)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(budget.snapshot().calls()).isZero();
    org.mockito.Mockito.verifyNoInteractions(delegate);
  }

  @Test
  void pinsDefaultTierWithoutMutatingTheAcceptedPromptOrItsContext() {
    var options =
        OpenAiResponsesChatOptions.builder()
            .model("fixture")
            .maxOutputTokens(2048)
            .toolContext(Map.of("owned-context", "retained"))
            .build();
    when(delegate.call(any(Prompt.class)))
        .thenAnswer(invocation -> {
          var actual = (OpenAiResponsesChatOptions) invocation.<Prompt>getArgument(0).getOptions();
          assertThat(actual.getServiceTier()).isEqualTo("default");
          assertThat(actual.getToolContext()).containsEntry("owned-context", "retained");
          return response();
        });
    var budget = budget();
    var model = new ResponsesAcceptanceMeter(delegate, budget, true);
    model.call(new Prompt("synthetic", options));
    when(delegate.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          var actual = (OpenAiResponsesChatOptions) invocation.<Prompt>getArgument(0).getOptions();
          assertThat(actual.getServiceTier()).isEqualTo("default");
          return reactor.core.publisher.Flux.just(response());
        });
    model.stream(new Prompt("synthetic", options)).collectList().block();
    assertThat(options.getServiceTier()).isNull();
    assertThat(budget.snapshot().completed()).isEqualTo(2);
  }

  @Test
  void refusesCallerTierOverrideBeforeReservingOrCalling() {
    when(delegate.call(any(Prompt.class))).thenReturn(response());
    var budget = budget();
    var options =
        OpenAiResponsesChatOptions.builder()
            .model("fixture")
            .maxOutputTokens(2048)
            .serviceTier("auto")
            .build();
    assertThatThrownBy(() ->
            new ResponsesAcceptanceMeter(delegate, budget, true)
                .call(new Prompt("synthetic", options)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(budget.snapshot().calls()).isZero();
    org.mockito.Mockito.verifyNoInteractions(delegate);
  }

  @Test
  void localProfileDoesNotReceiveAnOpenAiTierParameter() {
    var options =
        OpenAiResponsesChatOptions.builder().model("fixture").maxOutputTokens(2048).build();
    when(delegate.call(any(Prompt.class)))
        .thenAnswer(invocation -> {
          assertThat(
                  ((OpenAiResponsesChatOptions) invocation.<Prompt>getArgument(0).getOptions())
                      .getServiceTier())
              .isNull();
          return response();
        });
    new ResponsesAcceptanceMeter(delegate, budget(), false).call(new Prompt("synthetic", options));
  }
}
