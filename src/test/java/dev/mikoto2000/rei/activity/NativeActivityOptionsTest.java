package dev.mikoto2000.rei.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import reactor.core.publisher.Flux;

class NativeActivityOptionsTest {
  private OpenAiResponsesChatOptions base(int limit) {
    return OpenAiResponsesChatOptions.builder()
        .model("native-activity")
        .maxOutputTokens(limit)
        .build();
  }

  private void verifyOptions(Prompt prompt, int limit, String schema) {
    var sent = (OpenAiResponsesChatOptions) prompt.getOptions();
    assertThat(sent.getMaxOutputTokens()).isEqualTo(Math.min(limit, 2048));
    assertThat(sent.getResponseFormat().getJsonSchema()).isEqualTo(schema);
    assertThat(sent.getResponseFormat().getStrict()).isTrue();
    assertThat(sent.getToolCallbacks()).isEmpty();
    assertThat(sent.getToolChoice()).isNull();
    assertThat(sent.getToolContext().get("rei.llm.replay-owner")).asString().startsWith("feature:");
  }

  @ParameterizedTest
  @ValueSource(ints = {41, 16000})
  void temporalUsesNativeStructuredOptions(int limit) throws Exception {
    var model = mock(ChatModel.class);
    var options = base(limit);
    when(model.stream(any(Prompt.class)))
        .thenAnswer(inv -> {
          verifyOptions(inv.getArgument(0), limit, LlmTemporalActivityModel.SCHEMA);
          return Flux.just(
              LlmTemporalActivityModelTest.response(
                  "{\"activity\":\"fixture\",\"confidence\":0.6}", 100));
        });
    var extractor =
        LlmTemporalActivityModel.class
            .getConstructor(Supplier.class, Supplier.class)
            .newInstance(
                (Supplier<ChatModel>) () -> model,
                (Supplier<OpenAiResponsesChatOptions>) () -> options);
    assertThat(extractor.infer("{}", 2048, 2, 200).totalTokens()).isEqualTo(100);
    assertThat(options.getResponseFormat()).isNull();
    assertThat(options.getMaxOutputTokens()).isEqualTo(limit);
  }

  @ParameterizedTest
  @ValueSource(ints = {41, 16000})
  void dailySummaryUsesNativeStructuredOptions(int limit) throws Exception {
    var model = mock(ChatModel.class);
    var options = base(limit);
    var fixture = new DailySummaryWriterTest();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(inv -> {
          verifyOptions(inv.getArgument(0), limit, LlmDailySummaryWriter.SCHEMA);
          return Flux.just(LlmTemporalActivityModelTest.response(fixture.valid(), 100));
        });
    var writer =
        LlmDailySummaryWriter.class
            .getConstructor(Supplier.class, Supplier.class, Duration.class)
            .newInstance(
                (Supplier<ChatModel>) () -> model,
                (Supplier<OpenAiResponsesChatOptions>) () -> options,
                Duration.ofSeconds(2));
    assertThat(writer.write(fixture.aggregate()).workThemes()).containsExactly("rei の開発");
    assertThat(options.getResponseFormat()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {41, 16000})
  void visionUsesNativeStructuredOptionsAndKeepsImage(int limit) throws Exception {
    var model = mock(ChatModel.class);
    var options = base(limit);
    when(model.call(any(Prompt.class)))
        .thenAnswer(inv -> {
          Prompt prompt = inv.getArgument(0);
          verifyOptions(prompt, limit, ForegroundActivityParser.SCHEMA);
          assertThat(prompt.getUserMessage().getMedia()).hasSize(2);
          return LlmTemporalActivityModelTest.response(ForegroundActivityVisionTest.VALID, 100);
        });
    var extractor =
        VisionActivityExtractor.class
            .getConstructor(Supplier.class, Supplier.class)
            .newInstance(
                (Supplier<ChatModel>) () -> model,
                (Supplier<OpenAiResponsesChatOptions>) () -> options);
    try (var scope = org.slf4j.MDC.putCloseable("activityScope", "foreground")) {
      assertThat(
              extractor.extract(
                  ActivityCaptureTest.screen(20), new ForegroundWindow("fixture", 1, "", "1")))
          .isNotNull();
    }
    assertThat(options.getResponseFormat()).isNull();
  }
}
