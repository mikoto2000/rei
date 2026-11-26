package dev.mikoto2000.rei.core.policy;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Private, short-lived input for content-bound inference approval; never a persisted request. */
final class InferenceExportRequest {
  private final Prompt prompt;
  private final String fingerprint;

  private InferenceExportRequest(Prompt prompt, String fingerprint) {
    this.prompt = prompt;
    this.fingerprint = fingerprint;
  }

  static InferenceExportRequest prepare(Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options))
      throw new IllegalArgumentException("Inference export requires explicit model options");
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    var encoded = prompt.getInstructions().stream().map(codec::encode).toList();
    var messages = encoded.stream().map(codec::decode).toList();
    var supplied = options.getToolCallbacks();
    var callbacks =
        supplied == null
            ? java.util.List.<ToolCallback>of()
            : supplied.stream().map(InferenceExportRequest::snapshot).toList();
    var frozen = new Prompt(messages, options.mutate().toolCallbacks(callbacks).build());
    var frozenOptions = (ToolCallingChatOptions) frozen.getOptions();
    var context = frozenOptions.getToolContext();
    var captured =
        context == null
            ? null
            : context.get(dev.mikoto2000.rei.modeloptions.ModelOptions.PREPARATION_CONTEXT_KEY);
    String preparation =
        captured == null
            ? dev.mikoto2000.rei.modeloptions.ModelOptions.preparationFingerprint(frozen)
            : captured instanceof String value ? value : null;
    return new InferenceExportRequest(
        frozen, fingerprint(preparation, definitions(callbacks), messages));
  }

  static String fingerprint(
      String preparation,
      java.util.List<ApprovalContinuationStore.InferenceTool> tools,
      java.util.List<org.springframework.ai.chat.messages.Message> messages) {
    if (preparation == null || !preparation.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid inference content preparation binding");
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      add(digest, "rei.inference-export.v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      add(digest, preparation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      for (var tool : tools) {
        add(digest, tool.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        add(digest, tool.description().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        add(digest, tool.schema().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) (tool.returnDirect() ? 1 : 0));
        digest.update((byte) (tool.requiresConfirmation() ? 1 : 0));
      }
      var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
      for (var message : messages) add(digest, codec.encode(message));
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Inference export digest unavailable");
    }
  }

  Prompt prompt() {
    return prompt;
  }

  String fingerprint() {
    return fingerprint;
  }

  ApprovalContinuationStore.InferenceInput input() {
    return new ApprovalContinuationStore.InferenceInput(
        fingerprint,
        definitions(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()));
  }

  private static java.util.List<ApprovalContinuationStore.InferenceTool> definitions(
      java.util.List<ToolCallback> callbacks) {
    return callbacks.stream()
        .map(callback -> {
          var definition = callback.getToolDefinition();
          var metadata = callback.getToolMetadata();
          return new ApprovalContinuationStore.InferenceTool(
              definition.name(),
              definition.description(),
              definition.inputSchema(),
              metadata.returnDirect(),
              metadata.requiresConfirmation());
        })
        .toList();
  }

  @Override
  public String toString() {
    return "InferenceExportRequest[fingerprint=" + fingerprint + "]";
  }

  private static void add(java.security.MessageDigest digest, byte[] value) {
    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static ToolCallback snapshot(ToolCallback callback) {
    var source = callback.getToolDefinition();
    var definition =
        ToolDefinition.builder()
            .name(source.name())
            .description(source.description())
            .inputSchema(source.inputSchema())
            .build();
    var sourceMetadata = callback.getToolMetadata();
    var metadata =
        ToolMetadata.builder()
            .returnDirect(sourceMetadata.returnDirect())
            .requiresConfirmation(sourceMetadata.requiresConfirmation())
            .build();
    return dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.preservePolicy(
        callback,
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return definition;
          }

          public ToolMetadata getToolMetadata() {
            return metadata;
          }

          public String call(String input) {
            return callback.call(input);
          }

          public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
            return callback.call(input, context);
          }
        });
  }
}
