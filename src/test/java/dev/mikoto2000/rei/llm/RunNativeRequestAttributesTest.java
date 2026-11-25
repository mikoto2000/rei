package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class RunNativeRequestAttributesTest {
  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void preservesTheAppliedRequestAttributes(LlmApiType api) {
    ToolCallingChatOptions source =
        api == LlmApiType.CHAT_COMPLETIONS
            ? OpenAiChatOptions.builder()
                .model("model")
                .toolChoice("none")
                .metadata(Map.of("purpose", "fixture"))
                .promptCacheKey("cache-fixture")
                .user("fixture-user")
                .replayReasoningContent(false)
                .streamOptions(OpenAiChatOptions.StreamOptions.builder().includeUsage(true).build())
                .build()
            : OpenAiResponsesChatOptions.builder()
                .model("model")
                .toolChoice("none")
                .metadata(Map.of("purpose", "fixture"))
                .promptCacheKey("cache-fixture")
                .safetyIdentifier("fixture-user")
                .build();
    var settings = RunNativeOptions.generation(source);
    assertThat(settings)
        .containsEntry("tool_choice", "none")
        .containsEntry("metadata", Map.of("purpose", "fixture"))
        .containsEntry("prompt_cache_key", "cache-fixture");
    if (api == LlmApiType.CHAT_COMPLETIONS) {
      assertThat(settings)
          .containsEntry("user", "fixture-user")
          .containsEntry("replay_reasoning_content", false)
          .containsEntry("stream_options", Map.of("include_usage", true));
    } else assertThat(settings).containsEntry("safety_identifier", "fixture-user");
    var restored = RunNativeOptions.options(connection(api, settings));
    assertThat(RunNativeOptions.generation(restored)).isEqualTo(settings);
  }

  @Test
  void refusesAudioUntilTheRunAdapterCanPreserveItsContract() {
    assertThatThrownBy(() ->
            RunNativeOptions.generation(
                OpenAiChatOptions.builder().outputModalities(List.of("audio")).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("modalities");
  }

  @Test
  void refusesHostedToolsUntilTheyHaveAnExplicitRunCapability() {
    // The existing host-owned tool loop must never turn into remote hosted execution.
    assertThatThrownBy(() ->
            RunNativeOptions.generation(
                OpenAiResponsesChatOptions.builder()
                    .hostedTools(
                        List.of(org.springframework.ai.openai.responses.HostedTool.WebSearch.of()))
                    .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hosted_tools");
  }

  private RunLlmConnection connection(LlmApiType api, Map<String, Object> generation) {
    return new RunLlmConnection(
        "fixture",
        true,
        LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE,
        null,
        null,
        new ResolvedLlmEndpoint(
            "fixture",
            1,
            api,
            "http://fixture.invalid/v1",
            "fixture",
            "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
            null,
            null,
            Duration.ofSeconds(5)),
        Map.of(),
        generation);
  }
}
