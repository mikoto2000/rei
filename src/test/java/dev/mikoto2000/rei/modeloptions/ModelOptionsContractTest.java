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
  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.Tag("integration")
  void anotherJvmCreatesTheSameStructuredOutputContract(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var result = directory.resolve("contract.txt");
    var log = directory.resolve("worker.log");
    var arguments = directory.resolve("worker.args");
    var values =
        List.of(
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            ContractWorker.class.getName(),
            result.toString());
    java.nio.file.Files.write(
        arguments,
        values.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .toList());
    var builder =
        new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder.environment().put("REI_DATA_DIR", directory.resolve("data").toString());
    var child = builder.start();
    boolean completed = child.waitFor(40, java.util.concurrent.TimeUnit.SECONDS);
    if (!completed) child.destroyForcibly();
    assertThat(completed).as("owned contract fixture JVM").isTrue();
    assertThat(child.exitValue())
        .as("fixture output: %s", java.nio.file.Files.readString(log))
        .isZero();
    assertThat(java.nio.file.Files.readString(result))
        .isEqualTo(ModelOptions.preparationFingerprint(structuredPrompt()));
  }

  private static Prompt structuredPrompt() {
    var options =
        OpenAiResponsesChatOptions.builder()
            .model("fixture-model")
            .maxOutputTokens(41)
            .responseFormat(format("{\"type\":\"object\"}"))
            .toolCallbacks(List.of(callback("{\"type\":\"object\"}")))
            .build();
    return new Prompt(
        List.of(new SystemMessage("original instructions"), new UserMessage("fixture")), options);
  }

  public static class ContractWorker {
    public static void main(String[] arguments) throws Exception {
      if (System.getenv("REI_DATA_DIR") == null || System.getenv("REI_DATA_DIR").isBlank())
        throw new IllegalStateException("Isolated fixture data directory required");
      java.nio.file.Files.writeString(
          java.nio.file.Path.of(arguments[0]),
          ModelOptions.preparationFingerprint(structuredPrompt()));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "model",
        "output",
        "temperature",
        "timeout",
        "organization",
        "project",
        "schema",
        "instructions",
        "credential",
        "format-instance",
        "format-schema"
      })
  void preparationContractDetectsExecutionChangesWhileAllowingKeyRotation(String changed) {
    var original =
        OpenAiResponsesChatOptions.builder()
            .model("fixture-model")
            .apiKey("fixture-secret-a")
            .maxOutputTokens(41)
            .temperature(0.2)
            .timeout(Duration.ofSeconds(20))
            .organizationId("fixture-org")
            .toolCallbacks(List.of(callback("{\"type\":\"object\"}")))
            .build();
    if (changed.startsWith("format"))
      original = original.mutate().responseFormat(format("{\"type\":\"object\"}")).build();
    var builder = original.mutate();
    switch (changed) {
      case "model" -> builder.model("changed-model");
      case "output" -> builder.maxOutputTokens(42);
      case "temperature" -> builder.temperature(0.3);
      case "timeout" -> builder.timeout(Duration.ofSeconds(21));
      case "organization" -> builder.organizationId("changed-org");
      case "project" ->
          builder.customHeaders(java.util.Map.of("OpenAI-Project", "changed-project"));
      case "schema" ->
          builder.toolCallbacks(List.of(callback("{\"type\":\"object\",\"required\":[\"path\"]}")));
      case "credential" -> builder.apiKey("fixture-secret-b");
      case "format-instance" -> builder.responseFormat(format("{\"type\":\"object\"}"));
      case "format-schema" ->
          builder.responseFormat(format("{\"type\":\"object\",\"required\":[\"path\"]}"));
      default -> {}
    }
    var first =
        new Prompt(
            List.of(new SystemMessage("original instructions"), new UserMessage("first request")),
            original);
    var next =
        new Prompt(
            List.of(
                new SystemMessage(
                    changed.equals("instructions")
                        ? "changed instructions"
                        : "original instructions"),
                new UserMessage("current request")),
            builder.build());
    String fingerprint = ModelOptions.preparationFingerprint(first);
    if (changed.equals("credential") || changed.equals("format-instance"))
      assertThat(ModelOptions.preparationFingerprint(next)).isEqualTo(fingerprint);
    else assertThat(ModelOptions.preparationFingerprint(next)).isNotEqualTo(fingerprint);
    assertThat(fingerprint)
        .matches("[0-9a-f]{64}")
        .doesNotContain("fixture-secret", "original instructions");
  }

  private static org.springframework.ai.openai.OpenAiChatModel.ResponseFormat format(
      String schema) {
    return org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.builder()
        .type(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
        .jsonSchema(schema)
        .strict(true)
        .build();
  }

  private static org.springframework.ai.tool.ToolCallback callback(String schema) {
    return new org.springframework.ai.tool.ToolCallback() {
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name("fixture")
            .description("fixture")
            .inputSchema(schema)
            .build();
      }

      public String call(String input) {
        throw new AssertionError("Contract hashing must not execute tools");
      }
    };
  }
}
