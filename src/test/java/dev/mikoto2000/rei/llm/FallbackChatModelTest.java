package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** 代替手段応答生成に使うモデルの結果と状態の契約を検証するテスト。 */
class FallbackChatModelTest {
  /** 代替手段について、設定済み代替手段モデルを保持することを確認する。 */
  @Test
  void fallbackRetainsConfiguredFallbackModelAndRequestOptionsForCallAndStream() {
    var primary = Mockito.mock(ChatModel.class);
    var fallback = Mockito.mock(ChatModel.class);
    when(fallback.getOptions())
        .thenReturn(OpenAiChatOptions.builder().model("local-fallback").build());
    var options =
        OpenAiChatOptions.builder()
            .model("feature-model")
            .temperature(0.3)
            .maxTokens(1234)
            .streamUsage(true)
            .toolContext(java.util.Map.of("run", "owner"))
            .build();
    var prompt = new Prompt("hello", options);
    when(primary.call(prompt)).thenThrow(new RuntimeException("unavailable"));
    when(primary.stream(prompt)).thenReturn(Flux.error(new RuntimeException("unavailable")));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(response("fallback"));
    when(fallback.stream(Mockito.any(Prompt.class))).thenReturn(Flux.just(response("fallback")));
    var model = new FallbackChatModel("chat", primary, fallback, "feature-model");

    model.call(prompt);
    model.stream(prompt).blockLast();

    var calls = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(calls.capture());
    verify(fallback).stream(calls.capture());
    for (var actual : calls.getAllValues()) {
      var actualOptions = (OpenAiChatOptions) actual.getOptions();
      assertThat(actualOptions.getModel()).isEqualTo("local-fallback");
      assertThat(actualOptions.getMaxTokens()).isEqualTo(1234);
      assertThat(actualOptions.getTemperature()).isEqualTo(0.3);
      assertThat(actualOptions.getToolContext()).containsEntry("run", "owner");
      assertThat(actualOptions.getStreamOptions().includeUsage()).isTrue();
    }
    assertThat(options.getModel()).isEqualTo("feature-model");
  }

  /** 用途に応じたモデル利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void ordinaryTransportErrorAfterRunCancellationDoesNotStartFallback() {
    var primary = Mockito.mock(ChatModel.class);
    var fallback = Mockito.mock(ChatModel.class);
    var context =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "run",
            new OutputLimitRunBudget(1, 3),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(java.nio.file.Path.of(".")),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    var prompt =
        new Prompt(
            "request",
            org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                .toolContext(
                    java.util.Map.of(
                        dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, context))
                .build());
    when(primary.stream(prompt))
        .thenAnswer(
            invocation -> {
              context.cancel();
              return Flux.error(new RuntimeException("connection closed"));
            });
    var model = new FallbackChatModel("chat", primary, fallback, null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.stream(prompt).blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    Mockito.verifyNoInteractions(fallback);
  }

  /** 用途に応じたモデル利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationDoesNotStartFallback() {
    var primary = Mockito.mock(ChatModel.class);
    var fallback = Mockito.mock(ChatModel.class);
    var prompt = new Prompt("request");
    when(primary.call(prompt)).thenThrow(new java.util.concurrent.CancellationException());
    when(primary.stream(prompt))
        .thenReturn(Flux.error(new java.util.concurrent.CancellationException()));
    var model = new FallbackChatModel("chat", primary, fallback, null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.call(prompt))
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.stream(prompt).blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    Mockito.verifyNoInteractions(fallback);
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void callFallsBackToDefaultModelWhenPrimaryFails() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt =
        new Prompt("hello", ChatOptions.builder().model("feature-model").temperature(0.2).build());
    ChatResponse fallbackResponse = response("fallback");

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(fallbackResponse);

    FallbackChatModel model = new FallbackChatModel("search", primary, fallback, "feature-model");

    assertThat(model.call(prompt)).isSameAs(fallbackResponse);
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isNull();
    assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.2);
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void streamFallsBackToDefaultModelWhenPrimaryFails() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", ChatOptions.builder().model("feature-model").build());
    ChatResponse fallbackResponse = response("fallback");

    when(primary.stream(prompt)).thenReturn(Flux.error(new RuntimeException("connection failed")));
    when(fallback.stream(Mockito.any(Prompt.class))).thenReturn(Flux.just(fallbackResponse));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");

    assertThat(model.stream(prompt).collectList().block()).containsExactly(fallbackResponse);
  }

  /** 呼び出しについて、プロンプトモデルを維持することを確認する。 */
  @Test
  void callKeepsPromptModelWhenItIsNotPrimaryFeatureModel() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt =
        new Prompt("hello", ChatOptions.builder().model("current-default-model").build());

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(response("fallback"));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");
    model.call(prompt);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("current-default-model");
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void callFallsBackWithoutCallingMutateWhenOptionsDoNotSupportMutate() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", new MinimalChatOptions("feature-model", 0.4));

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(response("fallback"));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");
    model.call(prompt);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isNull();
    assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.4);
  }

  /** 呼び出しについて、主要なモデルを取り除くことを確認する。 */
  @Test
  void callRemovesPrimaryModelFromOpenAiOptionsWhenFallingBack() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt =
        new Prompt(
            "hello", OpenAiChatOptions.builder().model("feature-model").temperature(0.5).build());

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(response("fallback"));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");
    model.call(prompt);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isNull();
    assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.5);
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  /**
   * 検証条件の再現に使う最小チャット選択可能な設定の情報。
   *
   * @param model 検証に使うモデル
   * @param temperature 検証に使う生成の揺らぎ
   */
  private record MinimalChatOptions(String model, Double temperature) implements ChatOptions {
    /** 検証用に、用途に応じたモデル利用で参照するモデルを返す。 */
    @Override
    public String getModel() {
      return model;
    }

    /** 検証用に、用途に応じたモデル利用で参照する生成の揺らぎを返す。 */
    @Override
    public Double getTemperature() {
      return temperature;
    }

    /** 検証用に、用途に応じたモデル利用で参照する実行頻度減点を返す。 */
    @Override
    public Double getFrequencyPenalty() {
      return null;
    }

    /** 検証用に、用途に応じたモデル利用で参照するトークン数の上限を返す。 */
    @Override
    public Integer getMaxTokens() {
      return null;
    }

    /** 検証用に、用途に応じたモデル利用で参照するPresence減点を返す。 */
    @Override
    public Double getPresencePenalty() {
      return null;
    }

    /** 検証用に、用途に応じたモデル利用で参照する停止発生順序一覧を返す。 */
    @Override
    public List<String> getStopSequences() {
      return null;
    }

    /** 検証用に、用途に応じたモデル利用で参照する上位件数を返す。 */
    @Override
    public Integer getTopK() {
      return null;
    }

    /** 検証用に、用途に応じたモデル利用で参照する上位検証用の設定を返す。 */
    @Override
    public Double getTopP() {
      return null;
    }

    /** {@code mutate} の条件を再現するための補助情報を返す。 */
    @Override
    public ChatOptions.Builder<?> mutate() {
      throw new UnsupportedOperationException();
    }
  }
}
