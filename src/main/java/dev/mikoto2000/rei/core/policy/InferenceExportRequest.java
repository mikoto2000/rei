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
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      add(digest, "rei.inference-export.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      add(
          digest,
          dev.mikoto2000.rei.modeloptions.ModelOptions.preparationFingerprint(frozen)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      for (var callback : callbacks) {
        add(
            digest,
            callback.getToolDefinition().name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) (callback.getToolMetadata().requiresConfirmation() ? 1 : 0));
      }
      for (byte[] message : encoded) add(digest, message);
      return new InferenceExportRequest(
          frozen, java.util.HexFormat.of().formatHex(digest.digest()));
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
