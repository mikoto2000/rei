package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class RunNativeGenerationRoundTripTest {
  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void capturesTheOtherAppliedGenerationFields(LlmApiType api) {
    assertThat(RunNativeOptions.generation(source(api))).containsAllEntriesOf(expected(api));
  }

  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void nativeReconstructionPreservesTheseFields(LlmApiType api) {
    var restored = RunNativeOptions.options(connection(api, expected(api)));
    assertThat(RunNativeOptions.generation(restored)).containsAllEntriesOf(expected(api));
  }

  private ToolCallingChatOptions source(LlmApiType api) {
    return api == LlmApiType.CHAT_COMPLETIONS
        ? OpenAiChatOptions.builder()
            .model("model")
            .seed(42)
            .frequencyPenalty(0.1)
            .presencePenalty(0.2)
            .stop(List.of("END"))
            .verbosity("low")
            .strict(false)
            .parallelToolCalls(false)
            .store(false)
            .logprobs(true)
            .topLogprobs(3)
            .n(1)
            .logitBias(Map.of("42", -1))
            .build()
        : OpenAiResponsesChatOptions.builder()
            .model("model")
            .reasoningSummary("auto")
            .verbosity("low")
            .strict(false)
            .parallelToolCalls(false)
            .maxToolCalls(3)
            .truncation("disabled")
            .include(List.of("reasoning.encrypted_content"))
            .build();
  }

  private Map<String, Object> expected(LlmApiType api) {
    if (api == LlmApiType.RESPONSES)
      return Map.of(
          "reasoning_summary",
          "auto",
          "verbosity",
          "low",
          "strict",
          false,
          "parallel_tool_calls",
          false,
          "max_tool_calls",
          3,
          "truncation",
          "disabled",
          "include",
          List.of("reasoning.encrypted_content"));
    return Map.ofEntries(
        Map.entry("seed", 42),
        Map.entry("frequency_penalty", 0.1),
        Map.entry("presence_penalty", 0.2),
        Map.entry("stop", List.of("END")),
        Map.entry("verbosity", "low"),
        Map.entry("strict", false),
        Map.entry("parallel_tool_calls", false),
        Map.entry("store", false),
        Map.entry("logprobs", true),
        Map.entry("top_logprobs", 3),
        Map.entry("n", 1),
        Map.entry("logit_bias", Map.of("42", -1)));
  }

  private RunLlmConnection connection(LlmApiType api, Map<String, Object> generation) {
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
            null,
            null,
            Duration.ofSeconds(30)),
        Map.of(),
        generation);
  }
}
