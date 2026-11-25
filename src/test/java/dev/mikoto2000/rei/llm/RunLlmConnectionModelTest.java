package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.modeloptions.ModelOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import reactor.core.publisher.Flux;

class RunLlmConnectionModelTest {
  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void callUsesTheAcceptedSnapshotAndDoesNotReadMutableSelection(LlmApiType api) {
    var delegate = mock(ChatModel.class);
    var response = new ChatResponse(List.of());
    when(delegate.call(any(Prompt.class))).thenReturn(response);
    var observed = new AtomicReference<RunLlmConnection>();
    var routing =
        new RunLlmConnectionModel(
            connection -> {
              observed.set(connection);
              return delegate;
            },
            connection -> {});
    var accepted = connection(api);
    assertThat(routing.call(prompt(api, accepted))).isSameAs(response);
    assertThat(observed.get()).isEqualTo(accepted);
    verify(delegate).call(any(Prompt.class));
  }

  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void missingOrMismatchedConnectionNeverResolvesATransport(LlmApiType api) {
    var resolutions = new java.util.concurrent.atomic.AtomicInteger();
    var routing =
        new RunLlmConnectionModel(
            connection -> {
              resolutions.incrementAndGet();
              return mock(ChatModel.class);
            },
            connection -> {});
    assertThatThrownBy(() -> routing.call(new Prompt("request", options(api))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connection");
    var bad =
        options(api)
            .mutate()
            .model("other-model")
            .toolContext(Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection(api)))
            .build();
    assertThatThrownBy(() -> routing.call(new Prompt("request", bad)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("model");
    assertThat(resolutions).hasValue(0);
  }

  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void streamVerifiesBindingAtEachSubscriptionRatherThanAtAssembly(LlmApiType api) {
    var available = new AtomicBoolean(true);
    var delegate = mock(ChatModel.class);
    var response = new ChatResponse(List.of());
    when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));
    var routing =
        new RunLlmConnectionModel(
            connection -> delegate,
            connection -> {
              if (!available.get()) throw new IllegalStateException("registration unavailable");
            });
    var stream = routing.stream(prompt(api, connection(api)));
    verifyNoInteractions(delegate);
    assertThat(stream.blockFirst()).isSameAs(response);
    available.set(false);
    assertThatThrownBy(stream::blockFirst)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("registration unavailable");
    verify(delegate, times(1)).stream(any(Prompt.class));
  }

  private Prompt prompt(LlmApiType api, RunLlmConnection connection) {
    return new Prompt(
        "request",
        options(api)
            .mutate()
            .toolContext(
                Map.of(
                    ModelOptions.RUN_CONNECTION_CONTEXT_KEY,
                    connection,
                    ModelOptions.ENDPOINT_CONTEXT_KEY,
                    connection.endpoint()))
            .build());
  }

  private ToolCallingChatOptions options(LlmApiType api) {
    return api == LlmApiType.CHAT_COMPLETIONS
        ? OpenAiChatOptions.builder().model("model").build()
        : OpenAiResponsesChatOptions.builder().model("model").build();
  }

  private RunLlmConnection connection(LlmApiType api) {
    return new RunLlmConnection(
        "local",
        true,
        LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE,
        null,
        null,
        new ResolvedLlmEndpoint(
            "local",
            1,
            api,
            "http://local.invalid/v1",
            "local",
            "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
            512,
            null,
            Duration.ofSeconds(30)),
        Map.of(),
        Map.of());
  }
}
