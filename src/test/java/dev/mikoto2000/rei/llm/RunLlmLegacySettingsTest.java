package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunLlmLegacySettingsTest {
  @Test
  void legacyEndpointAndAllAppliedSettingsAreCapturedInOneResolution() {
    var properties = new LlmProperties();
    var calls = new AtomicInteger();
    var factory = RunLlmConnectionFactory.withLegacySettings(new ChatEndpointCatalog(properties),
        properties, selected -> {
          calls.incrementAndGet();
          assertThat(selected).isEqualTo("selected-model");
          return new RunLlmConnectionFactory.LegacySettings(endpoint(selected),
              Map.of("top_p", 0.7, "max_completion_tokens", 512, "seed", 42));
        }, ignored -> null);
    var connection = factory.capture("default", "selected-model");
    assertThat(calls).hasValue(1);
    assertThat(connection.endpoint().model()).isEqualTo("selected-model");
    assertThat(connection.generation()).containsExactlyInAnyOrderEntriesOf(
        Map.of("top_p", 0.7, "max_completion_tokens", 512, "seed", 42));
  }

  @Test
  void legacyNativeOptionsCannotBeCopiedWholesaleIntoTheRun() {
    var properties = new LlmProperties();
    var factory = RunLlmConnectionFactory.withLegacySettings(new ChatEndpointCatalog(properties),
        properties, selected -> new RunLlmConnectionFactory.LegacySettings(endpoint("model"),
            Map.of("apiKey", "PRIVATE_CANARY", "top_p", 0.7)), ignored -> null);
    assertThatThrownBy(() -> factory.capture("default", null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("PRIVATE_CANARY");
  }

  private ResolvedLlmEndpoint endpoint(String model) {
    return new ResolvedLlmEndpoint("chat", 1, LlmApiType.CHAT_COMPLETIONS,
        "http://legacy.invalid/v1", "legacy-chat", model,
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), 512, null,
        Duration.ofSeconds(30));
  }
}
