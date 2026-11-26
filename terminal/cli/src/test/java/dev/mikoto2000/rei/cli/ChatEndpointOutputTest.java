package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class ChatEndpointOutputTest {
  private JsonNode endpoint() throws Exception {
    return new JsonMapper().readTree("""
        {"profile":{"id":"local","model":"model-a","provider":"openai-compatible",
          "api":"CHAT_COMPLETIONS","usagePath":"local-llm","authentication":"none",
          "accountRef":null,"capabilities":{"effective":{"STREAMING":"UNKNOWN"}}},
         "active":true,"authenticationState":"not-required",
         "unavailableReason":"unconfirmed streaming","generationSettings":{"temperature":0.5},
         "unappliedSettings":{"max_tokens":"unconfirmed"},"diagnostics":["output limit omitted"],
         "selectionScope":"process-wide; next Run; resets on restart; inference not verified",
         "unexpectedSecret":"PRIVATE_TOKEN"}
        """);
  }

  @Test
  void labelsExplainActiveStateUsageAuthenticationAndUnavailableReason() throws Exception {
    var text = ChatEndpointOutput.label(endpoint());
    for (var expected : new String[] {"local", "[active]", "model-a", "openai-compatible",
        "chat-completions", "local-llm", "none", "not-required", "unconfirmed streaming"})
      assertTrue(text.contains(expected), expected);
    assertFalse(text.contains("PRIVATE_TOKEN"));
  }

  @Test
  void detailsIncludeEffectiveSettingsAndSelectionScope() throws Exception {
    var text = ChatEndpointOutput.detail(endpoint());
    for (var expected : new String[] {"STREAMING", "UNKNOWN", "temperature", "0.5",
        "max_tokens", "unconfirmed", "output limit omitted", "next Run", "restart", "not verified"})
      assertTrue(text.contains(expected), expected);
    assertFalse(text.contains("PRIVATE_TOKEN"));
  }
}
