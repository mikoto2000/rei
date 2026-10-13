package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import reactor.core.publisher.Flux;
import static dev.mikoto2000.rei.core.contextbudget.ContextCompressionException.Reason.*;

class LlmConversationCompressorTest {
  private static final String PRIVATE = "private-provider-body-and-request-secret";

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void summaryBudgetOverridesInheritedLimitWithoutChangingParameterFamily(boolean completionTokens) {
    var captured = new AtomicReference<Prompt>();
    var source = completionTokens
        ? OpenAiChatOptions.builder().model("configured").maxCompletionTokens(4096).build()
        : OpenAiChatOptions.builder().model("configured").maxTokens(4096).build();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) {
        captured.set(prompt);
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("summary")))));
      }
    };
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.CHAT)).thenReturn(model);
    when(models.chatOptions(LlmFeature.CHAT, "configured")).thenReturn(source);
    @SuppressWarnings("unchecked") var provider = (ObjectProvider<LlmModelProvider>) mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(models);
    var compressor = new LlmConversationCompressor(provider, new ContextCompressionProperties());
    assertThat(compressor.summarize("", List.of(), 100, new Prompt("owner", source))).isEqualTo("summary");
    var options = (OpenAiChatOptions) captured.get().getOptions();
    assertThat(options.getModel()).isEqualTo("configured");
    assertThat(options.getToolCallbacks()).isEmpty();
    assertThat(options.getToolContext()).containsEntry("rei.modelCallPurpose", "context-summary");
    if (completionTokens) {
      assertThat(options.getMaxCompletionTokens()).isEqualTo(100);
      assertThat(options.getMaxTokens()).isNull();
      assertThat(source.getMaxCompletionTokens()).isEqualTo(4096);
    } else {
      assertThat(options.getMaxTokens()).isEqualTo(100);
      assertThat(options.getMaxCompletionTokens()).isNull();
      assertThat(source.getMaxTokens()).isEqualTo(4096);
    }
  }

  private LlmConversationCompressor compressor(ChatModel model, ContextCompressionProperties properties) {
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.CHAT)).thenReturn(model);
    when(models.chatOptions(eq(LlmFeature.CHAT), any())).thenReturn(OpenAiChatOptions.builder().build());
    @SuppressWarnings("unchecked") var provider = (ObjectProvider<LlmModelProvider>) mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(models);
    return new LlmConversationCompressor(provider, properties);
  }

  private ChatResponse response(String text, String finish) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(finish).build())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  \n\t"})
  void blankResponsesHaveTheirOwnReason(String text) {
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(text, "stop")));
    assertThatThrownBy(() -> compressor(model, new ContextCompressionProperties())
        .summarize("", List.of(), 100, new Prompt("owner")))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> assertThat(error.reason()).isEqualTo(EMPTY_SUMMARY));
  }

  @Test void thinkingOnlyLengthResponseIsOutputLimitInsteadOfEmptySummary() {
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("", "length")));
    assertThatThrownBy(() -> compressor(model, new ContextCompressionProperties())
        .summarize("", List.of(), 100, new Prompt("owner")))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> {
          assertThat(error.reason()).isEqualTo(OUTPUT_LIMIT);
          assertThat(error.getMessage()).contains("思考トークン");
        });
  }

  @Test void toolResponseCannotBecomeConversationSummary() {
    var model = mock(ChatModel.class);
    var output = AssistantMessage.builder().content("summary").toolCalls(List.of(
        new AssistantMessage.ToolCall("id", "function", "unrequested", "{}"))).build();
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(output)))));
    assertThatThrownBy(() -> compressor(model, new ContextCompressionProperties())
        .summarize("", List.of(), 100, new Prompt("owner")))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> assertThat(error.reason()).isEqualTo(INVALID_RESPONSE));
  }

  @ParameterizedTest
  @CsvSource({"401,AUTHENTICATION", "403,AUTHENTICATION", "404,MODEL_UNAVAILABLE", "408,TIMEOUT",
      "429,RATE_LIMIT", "500,MODEL_UNAVAILABLE", "504,TIMEOUT", "400,INVALID_RESPONSE"})
  void classifiesHttpStatusesWithoutProviderMessages(int status, ContextCompressionException.Reason expected) {
    var model = mock(ChatModel.class);
    var failure = org.springframework.web.reactive.function.client.WebClientResponseException.create(status,
        PRIVATE, org.springframework.http.HttpHeaders.EMPTY, PRIVATE.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        java.nio.charset.StandardCharsets.UTF_8);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException(PRIVATE, failure)));
    assertThatThrownBy(() -> compressor(model, new ContextCompressionProperties())
        .summarize("", List.of(), 100, new Prompt("owner")))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> {
          assertThat(error.reason()).isEqualTo(expected);
          assertThat(error.getMessage()).doesNotContain(PRIVATE);
          assertThat(error.getCause()).isNull();
          assertThat(error.getSuppressed()).isEmpty();
        });
  }

  @Test void sdkFailuresUseStatusInsteadOfTheirBody() {
    var model = mock(ChatModel.class);
    var failure = mock(com.openai.errors.RateLimitException.class);
    when(failure.statusCode()).thenReturn(429);
    when(failure.getMessage()).thenReturn(PRIVATE);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(failure));
    assertThatThrownBy(() -> compressor(model, new ContextCompressionProperties())
        .summarize("", List.of(), 100, new Prompt("owner")))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> {
          assertThat(error.reason()).isEqualTo(RATE_LIMIT);
          assertThat(error.getMessage()).doesNotContain(PRIVATE);
          assertThat(error.getCause()).isNull();
        });
  }

  @Test void timeoutCancelsTheSummaryStreamAndHasDedicatedReason() {
    var model = mock(ChatModel.class);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
    var properties = new ContextCompressionProperties();
    properties.setSummaryTimeoutSeconds(1);
    assertThatThrownBy(() -> compressor(model, properties).summarize("", List.of(), 100, new Prompt("owner")))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> assertThat(error.reason()).isEqualTo(TIMEOUT));
    assertThat(cancelled).isTrue();
  }

  @Test void transportTimeoutCauseKeepsItsSpecificReason() {
    var error = new com.openai.errors.OpenAIIoException(PRIVATE, new java.net.SocketTimeoutException(PRIVATE));
    assertThat(ContextCompressionException.from(error).reason()).isEqualTo(TIMEOUT);
    assertThat(ContextCompressionException.from(new com.openai.errors.OpenAIIoException(PRIVATE)).reason()).isEqualTo(TRANSPORT);
    assertThat(ContextCompressionException.from(new com.openai.errors.OpenAIInvalidDataException(PRIVATE)).reason()).isEqualTo(INVALID_RESPONSE);
    assertThat(ContextCompressionException.from(new IllegalStateException(PRIVATE)).reason()).isEqualTo(UNKNOWN);
  }

  @Test void cancellationIsStillControlFlowInsteadOfDiagnosticFailure() {
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new CancellationException()));
    assertThatThrownBy(() -> compressor(model, new ContextCompressionProperties())
        .summarize("", List.of(), 100, new Prompt("owner"))).isInstanceOf(CancellationException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void budgetsCannotBecomeSummaryFailures(boolean wrapped) {
    var model = mock(ChatModel.class);
    var stopped = new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(wrapped ? new IllegalStateException(PRIVATE, stopped) : stopped));
    assertThatThrownBy(() -> compressor(model, new ContextCompressionProperties())
        .summarize("", List.of(), 100, new Prompt("owner"))).isSameAs(stopped);
  }

  @Test void providerUsageIsReturnedIndependentlyOfTextEstimate() {
    var model = mock(ChatModel.class);
    var metadata = org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(20, 3)).build();
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(
        List.of(new Generation(new AssistantMessage("日本語を含む要約"))), metadata)));
    var summary = compressor(model, new ContextCompressionProperties())
        .summarizeResult("", List.of(), 100, new Prompt("owner"));
    assertThat(summary.text()).isEqualTo("日本語を含む要約");
    assertThat(summary.outputTokens()).isEqualTo(3);
  }

  @Test void absentAndZeroProviderUsageRemainUnknown() {
    var model = mock(ChatModel.class);
    var zero = org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(20, 0)).build();
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary", "stop")),
        Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("summary"))), zero)));
    var compressor = compressor(model, new ContextCompressionProperties());
    for (int call = 0; call < 2; call++) {
      var summary = compressor.summarizeResult("", List.of(), 100, new Prompt("owner"));
      assertThat(summary.text()).isEqualTo("summary");
      assertThat(summary.outputTokens()).isNull();
    }
  }

  @Test void legacyFunctionalCompressorsKeepUnknownUsageAndBudgetGuard() {
    ConversationCompressor compressor = (previous, messages, maxTokens, owner) -> "legacy summary";
    var owner = new Prompt("owner");
    assertThat(compressor.summarizeResult("", List.of(), 100, owner))
        .isEqualTo(new ConversationCompressor.Summary("legacy summary", null));
    var budget = mock(dev.mikoto2000.rei.llm.ModelCallBudget.class);
    assertThatThrownBy(() -> compressor.summarizeResult("", List.of(), 100, owner, budget))
        .isInstanceOf(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class)
        .hasMessageContaining("TOKEN_USAGE_UNKNOWN");
    verifyNoInteractions(budget);
  }

  @Test void summaryPurposeCopiesContextAndRetainsOwnerAuthority() {
    var captured = new AtomicReference<Prompt>();
    var original = OpenAiChatOptions.builder().toolContext(java.util.Map.of(
        "retained-owner-key", "retained-owner-value", "rei.modelCallPurpose", "chat")).build();
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
      captured.set(invocation.getArgument(0));
      return Flux.just(response("summary", "stop"));
    });
    compressor(model, new ContextCompressionProperties()).summarize("", List.of(), 100, new Prompt("owner", original));
    var sent = (OpenAiChatOptions) captured.get().getOptions();
    assertThat(sent.getToolContext()).containsEntry("retained-owner-key", "retained-owner-value")
        .containsEntry("rei.modelCallPurpose", "context-summary");
    assertThat(original.getToolContext()).containsEntry("rei.modelCallPurpose", "chat");
  }

  @Test void setupErrorsDoNotCarryProviderText() {
    @SuppressWarnings("unchecked") var provider = (ObjectProvider<LlmModelProvider>) mock(ObjectProvider.class);
    when(provider.getObject()).thenThrow(new IllegalStateException(PRIVATE));
    var compressor = new LlmConversationCompressor(provider, new ContextCompressionProperties());
    assertThatThrownBy(() -> compressor.summarize("", List.of(), 100, new Prompt("owner")))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> {
          assertThat(error.reason()).isEqualTo(UNKNOWN);
          assertThat(error.getMessage()).doesNotContain(PRIVATE);
          assertThat(error.getCause()).isNull();
        });
  }

  @Test void numericDiagnosticsAreSafeAndTypedFailuresStayIntact() {
    var error = new ContextCompressionException(INPUT_BUDGET, 4000, 3000);
    assertThat(error.actualTokens()).isEqualTo(4000);
    assertThat(error.limitTokens()).isEqualTo(3000);
    assertThat(error.reason().code()).isEqualTo("summary_input_budget");
    assertThat(ContextCompressionException.from(error)).isSameAs(error);
  }
}
