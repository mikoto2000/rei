package dev.mikoto2000.rei.modeloptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class ModelOptionsContractTest {
  @ParameterizedTest
  @ValueSource(strings = {"model", "output", "temperature", "timeout", "organization", "project", "schema", "instructions", "credential"})
  void preparationContractDetectsExecutionChangesWhileAllowingKeyRotation(String changed) {
    var original = OpenAiResponsesChatOptions.builder().model("fixture-model").apiKey("fixture-secret-a")
        .maxOutputTokens(41).temperature(0.2).timeout(Duration.ofSeconds(20)).organizationId("fixture-org")
        .toolCallbacks(List.of(callback("{\"type\":\"object\"}"))).build();
    var builder = original.mutate();
    switch (changed) {
      case "model" -> builder.model("changed-model");
      case "output" -> builder.maxOutputTokens(42);
      case "temperature" -> builder.temperature(0.3);
      case "timeout" -> builder.timeout(Duration.ofSeconds(21));
      case "organization" -> builder.organizationId("changed-org");
      case "project" -> builder.customHeaders(java.util.Map.of("OpenAI-Project", "changed-project"));
      case "schema" -> builder.toolCallbacks(List.of(callback("{\"type\":\"object\",\"required\":[\"path\"]}")));
      case "credential" -> builder.apiKey("fixture-secret-b");
      default -> { }
    }
    var first = new Prompt(List.of(new SystemMessage("original instructions"), new UserMessage("first request")), original);
    var next = new Prompt(List.of(new SystemMessage(changed.equals("instructions") ? "changed instructions" : "original instructions"),
        new UserMessage("current request")), builder.build());
    String fingerprint = ModelOptions.preparationFingerprint(first);
    if (changed.equals("credential")) assertThat(ModelOptions.preparationFingerprint(next)).isEqualTo(fingerprint);
    else assertThat(ModelOptions.preparationFingerprint(next)).isNotEqualTo(fingerprint);
    assertThat(fingerprint).matches("[0-9a-f]{64}").doesNotContain("fixture-secret", "original instructions");
  }

  private org.springframework.ai.tool.ToolCallback callback(String schema) {
    return new org.springframework.ai.tool.ToolCallback() {
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder().name("fixture").description("fixture").inputSchema(schema).build();
      }
      public String call(String input) { throw new AssertionError("Contract hashing must not execute tools"); }
    };
  }
}
