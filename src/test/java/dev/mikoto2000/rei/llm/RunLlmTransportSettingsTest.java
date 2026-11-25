package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.openai.OpenAiChatOptions;

class RunLlmTransportSettingsTest {
  @Test
  void requestDeadlineAndAccountAttributesAreFrozenSeparatelyFromReadIdleTimeout() throws Exception {
    var settings = new HashMap<>(Map.of("request_timeout", "PT2M", "organization_id", "org-fixture"));
    var accepted = connection(settings);
    settings.put("organization_id", "other-org");
    assertThat(accepted.transport()).containsEntry("organization_id", "org-fixture");
    assertThat(accepted.requestTimeout()).isEqualTo(Duration.ofMinutes(2));
    assertThat(accepted.endpoint().readTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(((OpenAiChatOptions) RunNativeOptions.options(accepted)).getTimeout()).isEqualTo(Duration.ofMinutes(2));
    var json = new ObjectMapper().findAndRegisterModules();
    var restored = json.readValue(json.writeValueAsBytes(accepted), RunLlmConnection.class);
    assertThat(restored).isEqualTo(accepted);
    assertThatThrownBy(() -> restored.transport().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void transportAccountAttributesAffectTheAcceptedIdentity() {
    assertThat(connection(Map.of("organization_id", "first")).fingerprint())
        .isNotEqualTo(connection(Map.of("organization_id", "second")).fingerprint());
  }

  @ParameterizedTest
  @ValueSource(strings = {"apiKey", "accessToken", "Authorization", "headers", "credential"})
  void transportMetadataCannotStoreCredentialsOrArbitraryHeaders(String field) {
    assertThatThrownBy(() -> connection(Map.of(field, "PRIVATE_CANARY")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("PRIVATE_CANARY");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT-1S", "PRIVATE_CANARY"})
  void invalidDeadlineIsRejectedWithoutEchoingItsValue(String value) {
    assertThatThrownBy(() -> connection(Map.of("request_timeout", value)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("PRIVATE_CANARY");
  }

  private RunLlmConnection connection(Map<String, String> transport) {
    return new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null,
        new ResolvedLlmEndpoint("local", 1, LlmApiType.CHAT_COMPLETIONS,
            "http://local.invalid/v1", "local", "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), null, null,
            Duration.ofSeconds(5)), Map.of(), Map.of(), transport);
  }
}
