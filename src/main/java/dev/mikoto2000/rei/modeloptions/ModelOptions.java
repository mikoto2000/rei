package dev.mikoto2000.rei.modeloptions;

import java.util.List;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Native request option transformations; no execution, storage or connection resolution. */
public final class ModelOptions {
  public static final String ENDPOINT_CONTEXT_KEY = "rei.llm.resolved-endpoint";
  public static final String MAX_TOOLS_CONTEXT_KEY = "rei.llm.max-tools";
  public static final String PREPARATION_CONTEXT_KEY = "rei.modelOptions.preparation-fingerprint";

  private ModelOptions() {}

  /** Stable execution identity, excluding credentials and ordinary conversation input. */
  public static String preparationFingerprint(org.springframework.ai.chat.prompt.Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options))
      throw new IllegalArgumentException("Tool request options required for continuation contract");
    var values = new java.util.ArrayList<String>();
    java.util.function.Consumer<Object> add =
        value -> values.add(value == null ? null : value.toString());
    add.accept(options.getClass().getName());
    add.accept(options.getModel());
    add.accept(options.getTemperature());
    add.accept(options.getTopP());
    if (options instanceof OpenAiResponsesChatOptions nativeOptions) {
      add.accept(nativeOptions.getMaxOutputTokens());
      add.accept(nativeOptions.getTimeout());
      add.accept(nativeOptions.getReasoningEffort());
      add.accept(nativeOptions.getReasoningSummary());
      add.accept(nativeOptions.getVerbosity());
      var format = nativeOptions.getResponseFormat();
      add.accept(format == null ? null : format.getType());
      add.accept(format == null ? null : format.getJsonSchema());
      add.accept(format == null ? null : format.getStrict());
      add.accept(nativeOptions.getOutputSchema());
      add.accept(nativeOptions.getStrict());
      add.accept(nativeOptions.getToolChoice());
      add.accept(nativeOptions.getParallelToolCalls());
      add.accept(nativeOptions.getMaxToolCalls());
      add.accept(nativeOptions.getHostedTools());
      add.accept(nativeOptions.getInclude());
      add.accept(nativeOptions.getTruncation());
      add.accept(nativeOptions.getServiceTier());
      add.accept(nativeOptions.getSafetyIdentifier());
      add.accept(nativeOptions.getOrganizationId());
      add.accept(nativeOptions.getExtraBody());
      var headers = new java.util.TreeMap<String, String>();
      var customHeaders = nativeOptions.getCustomHeaders();
      if (customHeaders != null)
        customHeaders.forEach((name, value) -> {
          String key = name.toLowerCase(java.util.Locale.ROOT);
          if (!java.util.Set.of("authorization", "api-key", "x-api-key").contains(key))
            headers.put(key, value);
        });
      add.accept(headers);
    } else add.accept(options.getMaxTokens());
    var configuredCallbacks = options.getToolCallbacks();
    var callbacks =
        configuredCallbacks == null
            ? List.<org.springframework.ai.tool.ToolCallback>of()
            : configuredCallbacks;
    callbacks.stream()
        .sorted(java.util.Comparator.comparing(callback -> callback.getToolDefinition().name()))
        .forEach(callback -> {
          var definition = callback.getToolDefinition();
          add.accept(definition.name());
          add.accept(definition.description());
          add.accept(definition.inputSchema());
          add.accept(callback.getToolMetadata().returnDirect());
        });
    for (var message : prompt.getInstructions())
      if (message instanceof org.springframework.ai.chat.messages.SystemMessage) {
        add.accept(message.getText());
        add.accept(new java.util.TreeMap<>(message.getMetadata()));
      }
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        digest.update((byte) (value == null ? 0 : 1));
        if (value != null) {
          byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
          digest.update(bytes);
        }
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Select explicitly published callbacks using the native automatic function-call shape. */
  public static ToolCallingChatOptions withTools(
      ToolCallingChatOptions options, List<org.springframework.ai.tool.ToolCallback> callbacks) {
    if (callbacks.isEmpty()) return withoutTools(options);
    if (options instanceof OpenAiResponsesChatOptions responses) {
      var builder = responses.mutate();
      builder.toolCallbacks(callbacks);
      builder.toolChoice("auto");
      return builder.build();
    }
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.toolCallbacks(callbacks);
      builder.toolChoice("auto");
      return builder.build();
    }
    return options.mutate().toolCallbacks(callbacks).build();
  }

  /** Remove explicit callbacks and use the native tool-less request shape. */
  public static ToolCallingChatOptions withoutTools(ToolCallingChatOptions options) {
    if (options instanceof OpenAiResponsesChatOptions responses) {
      var builder = responses.mutate();
      builder.toolCallbacks(List.of());
      builder.toolChoice(null);
      return builder.build();
    }
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.toolCallbacks(List.of());
      builder.toolChoice("none");
      return builder.build();
    }
    return options.mutate().toolCallbacks(List.of()).build();
  }
}
