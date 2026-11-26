package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RunProfileTransportSettingsTest {
  private static final String PREFIX = "rei.llm.features.chat.endpoints.local.";

  @Test
  void eachAcceptedSnapshotFreezesTheConfiguredTransportControls() throws Exception {
    var values = settings();
    values.put(PREFIX + "request-limit-bytes", "128");
    values.put(PREFIX + "response-limit-bytes", "512");
    values.put(PREFIX + "connect-timeout", "2s");
    values.put(PREFIX + "read-timeout", "3s");
    values.put(PREFIX + "request-timeout", "4s");
    var properties = properties(values);
    var factory = factory(properties);
    var accepted = factory.capture("local", null);
    assertThat(accepted.transport())
        .containsEntry("request_limit_bytes", "128")
        .containsEntry("response_limit_bytes", "512")
        .containsEntry("connect_timeout", "PT2S")
        .containsEntry("request_timeout", "PT4S");
    assertThat(accepted.endpoint().readTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(accepted.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
    values.put(PREFIX + "request-limit-bytes", "256");
    values.put(PREFIX + "response-limit-bytes", "1024");
    properties.setFeature(LlmFeature.CHAT, properties(values).feature(LlmFeature.CHAT));
    properties.setReadTimeout(Duration.ofSeconds(9));
    assertThat(factory.capture("local", null)).isEqualTo(accepted);
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    assertThat(json.readValue(json.writeValueAsBytes(accepted), RunLlmConnection.class))
        .isEqualTo(accepted);
    assertThatThrownBy(() -> accepted.transport().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void omittedLimitsAreStillExplicitlyFrozenInANewRun() {
    var accepted = factory(properties(settings())).capture("local", null);
    assertThat(accepted.transport())
        .containsEntry("request_limit_bytes", "33554432")
        .containsEntry("response_limit_bytes", "67108864");
  }

  @ParameterizedTest
  @CsvSource({
    "request-limit-bytes,0",
    "request-limit-bytes,-1",
    "request-limit-bytes,134217729",
    "response-limit-bytes,0",
    "response-limit-bytes,-1",
    "response-limit-bytes,134217729",
    "connect-timeout,PT0S",
    "connect-timeout,PT0.000000001S",
    "read-timeout,PT0S",
    "read-timeout,PT0.000000001S",
    "request-timeout,PT0S",
    "request-timeout,PT0.000000001S"
  })
  void invalidExplicitControlsAreRejectedDuringConfiguration(String field, String value) {
    var values = settings();
    values.put(PREFIX + field, value);
    assertThatThrownBy(() -> properties(values)).isInstanceOf(Exception.class);
  }

  private static RunLlmConnectionFactory factory(LlmProperties properties) {
    return new RunLlmConnectionFactory(
        new ChatEndpointCatalog(properties),
        properties,
        ignored -> {
          throw new AssertionError("No legacy fallback");
        },
        ignored -> null);
  }

  private static LlmProperties properties(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }

  private static HashMap<String, String> settings() {
    var values = new HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "local");
    values.put(PREFIX + "base-url", "http://owned-fixture.invalid/v1");
    values.put(PREFIX + "model", "fixture-model");
    values.put(PREFIX + "authentication", "none");
    values.put(PREFIX + "api", "responses");
    values.put(PREFIX + "reasoning-replay", "none");
    values.put(PREFIX + "server-capabilities.responses", "supported");
    values.put(PREFIX + "server-capabilities.streaming", "supported");
    return values;
  }
}
