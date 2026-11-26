package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LegacyRunBindingTest {
  @Test
  void capturesResolvedAuthenticationAndTransportWithoutCredentials() {
    var mode = new AtomicReference<>(LlmAuthentication.NONE);
    var transport =
        new AtomicReference<>(Map.of("organization_id", "org-fixture", "request_timeout", "PT2M"));
    var accepted = factory(mode, transport).capture("default", "model");
    assertThat(accepted.authentication()).isEqualTo(LlmAuthentication.NONE);
    assertThat(accepted.transport()).containsEntry("organization_id", "org-fixture");
    assertThat(accepted.requestTimeout()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void restartCannotUpgradeAPinnedNoAuthRunToAnApiKey() {
    var mode = new AtomicReference<>(LlmAuthentication.NONE);
    var transport = new AtomicReference<>(Map.<String, String>of());
    var factory = factory(mode, transport);
    var accepted = factory.capture("default", "model");
    mode.set(LlmAuthentication.API_KEY);
    assertThatThrownBy(() -> factory.verifyBinding(accepted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authentication");
  }

  @Test
  void restartCannotMoveAnAcceptedRunToAnotherOrganization() {
    var mode = new AtomicReference<>(LlmAuthentication.API_KEY);
    var transport = new AtomicReference<>(Map.of("organization_id", "first"));
    var factory = factory(mode, transport);
    var accepted = factory.capture("default", "model");
    transport.set(Map.of("organization_id", "second"));
    assertThatThrownBy(() -> factory.verifyBinding(accepted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("binding");
  }

  @Test
  void currentDeadlineDoesNotReplaceAnAcceptedDeadline() {
    var mode = new AtomicReference<>(LlmAuthentication.API_KEY);
    var transport = new AtomicReference<>(Map.of("request_timeout", "PT2M"));
    var factory = factory(mode, transport);
    var accepted = factory.capture("default", "model");
    transport.set(Map.of("request_timeout", "PT1M"));
    factory.verifyBinding(accepted);
    assertThat(accepted.requestTimeout()).isEqualTo(Duration.ofMinutes(2));
  }

  private RunLlmConnectionFactory factory(
      AtomicReference<LlmAuthentication> mode, AtomicReference<Map<String, String>> transport) {
    var properties = new LlmProperties();
    return RunLlmConnectionFactory.withLegacySettings(
        new ChatEndpointCatalog(properties),
        properties,
        model ->
            new RunLlmConnectionFactory.LegacySettings(
                new ResolvedLlmEndpoint(
                    "chat",
                    1,
                    LlmApiType.CHAT_COMPLETIONS,
                    "http://legacy.invalid/v1",
                    "legacy-chat",
                    "model",
                    new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
                    null,
                    null,
                    Duration.ofSeconds(5)),
                Map.of(),
                mode.get(),
                transport.get()),
        ignored -> null);
  }
}
