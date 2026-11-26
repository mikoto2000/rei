package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatEndpointScopeTest {
  @Test
  void serializedEndpointExplainsGlobalNextRunSelectionAndNoPersistenceOrInferenceVerification() {
    var profile = new ChatEndpointCatalog.Profile("local", "fixture-model", LlmProvider.OPENAI_COMPATIBLE,
        LlmApiType.CHAT_COMPLETIONS, LlmAuthentication.NONE, null, 1, LlmUsagePath.UNKNOWN,
        new LlmEndpointCapabilities(Map.of(), Map.of(), Map.of(), 10));
    var endpoint = new ChatConnectionService.Endpoint(profile, true, ReasoningReplayMode.NONE,
        "not-required", null, Map.of(), Map.of(), List.of());
    var json = new ObjectMapper().valueToTree(endpoint);
    assertThat(json.path("selectionScope").asText()).contains("process", "next Run", "restart", "not verified");
  }
}
