package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.modeloptions.ModelOptions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class RunLlmConnectionFingerprintTest {
  @Test
  void fingerprintIsCanonicalAcrossMapOrderAndDurableRoundTrip() throws Exception {
    var first = new LinkedHashMap<String, Object>();
    first.put("temperature", 0.5);
    first.put("max_tokens", 1024);
    var reverse = new LinkedHashMap<String, Object>();
    reverse.put("max_tokens", 1024);
    reverse.put("temperature", 0.5);
    var a = connection(LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "registration", first);
    var b = connection(LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "registration", reverse);
    var json = new ObjectMapper().findAndRegisterModules();
    var restored = json.readValue(json.writeValueAsBytes(a), RunLlmConnection.class);
    assertThat(a.fingerprint()).matches("[a-f0-9]{64}");
    assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).isEqualTo(restored.fingerprint());
  }

  @Test
  void providerAuthenticationRegistrationAndGenerationArePartOfIdentity() {
    var baseline = connection(LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "registration", Map.of());
    assertThat(baseline.fingerprint()).isNotEqualTo(connection(
        LlmProvider.OPENAI_API, LlmAuthentication.API_KEY, "registration", Map.of()).fingerprint());
    assertThat(baseline.fingerprint()).isNotEqualTo(connection(
        LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.NONE, "registration", Map.of()).fingerprint());
    assertThat(baseline.fingerprint()).isNotEqualTo(connection(
        LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "new-registration", Map.of()).fingerprint());
    assertThat(baseline.fingerprint()).isNotEqualTo(connection(
        LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "registration", Map.of("seed", 42)).fingerprint());
  }

  @Test
  void approvalIdentityForAPinnedRunDoesNotDependOnARotatingCredential() {
    var connection = connection(LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "registration", Map.of());
    assertThat(ModelOptions.connectionFingerprint(options(connection, "PRIVATE_FIRST")))
        .isEqualTo(ModelOptions.connectionFingerprint(options(connection, "PRIVATE_REFRESHED")));
  }

  @Test
  void preparationIdentityIncludesTheAcceptedAccountRegistration() {
    var first = connection(LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "registration", Map.of());
    var other = connection(LlmProvider.OPENAI_COMPATIBLE, LlmAuthentication.API_KEY, "new-registration", Map.of());
    assertThat(ModelOptions.preparationFingerprint(new Prompt("request", options(first, null))))
        .isNotEqualTo(ModelOptions.preparationFingerprint(new Prompt("request", options(other, null))));
  }

  private OpenAiChatOptions options(RunLlmConnection connection, String key) {
    return OpenAiChatOptions.builder().model("model").apiKey(key)
        .toolContext(Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection,
            ModelOptions.ENDPOINT_CONTEXT_KEY, connection.endpoint())).build();
  }

  private RunLlmConnection connection(LlmProvider provider, LlmAuthentication authentication,
      String registration, Map<String, Object> generation) {
    return new RunLlmConnection("endpoint", true, provider, authentication, "account", registration,
        new ResolvedLlmEndpoint("endpoint", 1, LlmApiType.CHAT_COMPLETIONS,
            "http://local.invalid/v1", "endpoint", "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), 1024, null,
            Duration.ofSeconds(30)), Map.of(), generation);
  }
}
