package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;
import java.nio.file.Files;
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
import reactor.core.publisher.Flux;

@Tag("integration")
class ResponsesAcceptanceMeterTest {
  @TempDir Path root;
  private final ChatModel delegate = org.mockito.Mockito.mock(ChatModel.class);

  private ResponsesAcceptanceBudget budget() {
    return new ResponsesAcceptanceBudget(
        root.resolve("usage.jsonl"),
        "approved-target",
        new ResponsesAcceptanceBudget.Limits(
            5, 1000000, 300000, Duration.ofHours(3), 20000000, 10, 30),
        Clock.systemUTC());
  }

  private OpenAiResponsesChatOptions options() {
    return OpenAiResponsesChatOptions.builder()
        .model("fixture")
        .apiKey("PRIVATE_CREDENTIAL")
        .maxOutputTokens(2000)
        .build();
  }

  private ChatResponse response(boolean usage) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .parts(
                        List.of(
                            new org.springframework.ai.chat.messages.part.TextPart(
                                "done", null, Map.of())))
                    .properties(
                        usage
                            ? Map.of(
                                ModelTokenUsage.METADATA_KEY,
                                new ModelTokenUsage(40L, 20L, 60L, 0L, 10L))
                            : Map.of())
                    .build())));
  }

  @Test
  void syncReservesBeforeTheDelegateAndSettlesOnlyNumericUsage() throws Exception {
    var budget = budget();
    when(delegate.call(any(Prompt.class)))
        .thenAnswer(invocation -> {
          assertThat(budget.snapshot().calls()).isEqualTo(1);
          assertThat(budget.snapshot().completed()).isZero();
          return response(true);
        });
    var model = new ResponsesAcceptanceMeter(delegate, budget);
    assertThat(model.call(new Prompt("synthetic", options())).getResult().getOutput().getText())
        .isEqualTo("done");
    assertThat(budget.snapshot().completed()).isEqualTo(1);
    assertThat(budget.snapshot().input()).isEqualTo(40);
    assertThat(Files.readString(root.resolve("usage.jsonl")))
        .doesNotContain("PRIVATE_CREDENTIAL", "synthetic", "done");
  }

  @Test
  void terminalStreamingUsageIsAccountedExactlyOnce() {
    var budget = budget();
    when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response(false), response(true)));
    var model = new ResponsesAcceptanceMeter(delegate, budget);
    assertThat(model.stream(new Prompt("synthetic", options())).collectList().block()).hasSize(2);
    assertThat(budget.snapshot().calls()).isEqualTo(1);
    assertThat(budget.snapshot().completed()).isEqualTo(1);
    assertThat(budget.snapshot().output()).isEqualTo(20);
  }

  @Test
  void cancellationDoesNotRefundAnUnfinishedProviderCall() {
    var budget = budget();
    when(delegate.stream(any(Prompt.class))).thenReturn(Flux.never());
    var active =
        new ResponsesAcceptanceMeter(delegate, budget)
            .stream(new Prompt("synthetic", options())).subscribe();
    active.dispose();
    assertThat(budget.snapshot().calls()).isEqualTo(1);
    assertThat(budget.snapshot().unknown()).isEqualTo(1);
    assertThat(budget.snapshot().output()).isEqualTo(2000);
  }

  @Test
  void errorsAndMissingFinalUsageStopWithoutInventingZeroTokens() {
    var budget = budget();
    when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response(true), response(false)));
    assertThatThrownBy(() ->
            new ResponsesAcceptanceMeter(delegate, budget)
                .stream(new Prompt("synthetic", options())).blockLast())
        .isInstanceOf(IllegalStateException.class);
    assertThat(budget.snapshot().completed()).isZero();
    assertThat(budget.snapshot().unknown()).isEqualTo(1);
    when(delegate.call(any(Prompt.class))).thenThrow(new IllegalStateException("owned fixture"));
    assertThatThrownBy(() ->
            new ResponsesAcceptanceMeter(delegate, budget).call(new Prompt("synthetic", options())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(budget.snapshot().unknown()).isEqualTo(2);
  }

  @Test
  void oversizedPromptsAreRejectedBeforeCallingTheProvider() {
    var budget = budget();
    var model = new ResponsesAcceptanceMeter(delegate, budget);
    assertThatThrownBy(() -> model.call(new Prompt("x".repeat(300000), options())))
        .isInstanceOf(IllegalStateException.class);
    org.mockito.Mockito.verifyNoInteractions(delegate);
    assertThat(budget.snapshot().calls()).isZero();
  }
}
