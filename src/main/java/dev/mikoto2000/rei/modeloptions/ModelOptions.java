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
  public static final String SINGLE_REQUEST_CONTEXT_KEY = "rei.modelOptions.single-request";

  private ModelOptions() {}

  /** Host-owned context observations may change while the execution policy stays fixed. */
  public static boolean isContextData(org.springframework.ai.chat.messages.Message message) {
    if (!(message instanceof org.springframework.ai.chat.messages.SystemMessage)) return false;
    var metadata = message.getMetadata();
    return java.util.List.of(
                "rei.workContext", "rei.longTermMemory", "rei.workingSet", "rei.resumeContext")
            .stream()
            .anyMatch(key -> Boolean.TRUE.equals(metadata.get(key)))
        || metadata.get("rei.historyGeneration") instanceof String
        || metadata.get("rei.historySequence") instanceof Long
        || metadata.get("rei.historySequence") instanceof Integer;
  }

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
      if (message instanceof org.springframework.ai.chat.messages.SystemMessage
          && !isContextData(message)) {
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

  /**
   * Opaque digest for approval invalidation; connection credentials must never leave this method.
   */
  public static String connectionFingerprint(ToolCallingChatOptions options) {
    String baseUrl, apiKey;
    if (options instanceof OpenAiResponsesChatOptions responses) {
      baseUrl = responses.getBaseUrl();
      apiKey = responses.getApiKey();
    } else if (options instanceof OpenAiChatOptions chat) {
      baseUrl = chat.getBaseUrl();
      apiKey = chat.getApiKey();
    } else throw new IllegalArgumentException("Unknown connection option protocol");
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      for (var value :
          java.util.Arrays.asList(
              options.getClass().getName(),
              baseUrl,
              apiKey,
              options.getModel(),
              options.getToolContext() == null
                  ? null
                  : options.getToolContext().get(ENDPOINT_CONTEXT_KEY))) {
        digest.update((byte) (value == null ? 0 : 1));
        if (value != null) {
          byte[] bytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
          digest.update(bytes);
        }
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** A one-shot feature call owns no conversation replay or durable tool-loop authority. */
  public static ToolCallingChatOptions singleRequest(ToolCallingChatOptions options) {
    return singleRequest(options, java.util.Map.of());
  }

  public static ToolCallingChatOptions singleRequest(
      ToolCallingChatOptions options, java.util.Map<String, Object> callerContext) {
    var clean = withoutTools(options);
    var context = new java.util.HashMap<String, Object>(callerContext);
    for (String key :
        List.of(
            "rei.llm.replay-owner",
            ENDPOINT_CONTEXT_KEY,
            MAX_TOOLS_CONTEXT_KEY,
            PREPARATION_CONTEXT_KEY,
            SINGLE_REQUEST_CONTEXT_KEY,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_GRANT_CONTEXT_KEY))
      context.remove(key);
    var ownedContext = clean.getToolContext();
    if (!(clean instanceof OpenAiResponsesChatOptions)) {
      if (ownedContext != null) context.putAll(ownedContext);
      return clean.mutate().toolContext(null).toolContext(context).build();
    }
    if (ownedContext != null) context.putAll(ownedContext);
    for (String key :
        List.of(
            "rei.llm.replay-owner",
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_GRANT_CONTEXT_KEY))
      if (context.containsKey(key))
        throw new IllegalArgumentException("A feature request cannot inherit replay authority");
    context.put("rei.llm.replay-owner", "feature:" + java.util.UUID.randomUUID());
    context.put(SINGLE_REQUEST_CONTEXT_KEY, true);
    return clean.mutate().toolContext(null).toolContext(context).build();
  }

  /** Preserve the selected API's output-token parameter family. */
  public static ToolCallingChatOptions withOutputLimit(ToolCallingChatOptions options, int tokens) {
    if (tokens < 1) throw new IllegalArgumentException("Output limit must be positive");
    if (options instanceof OpenAiResponsesChatOptions responses) {
      var builder = responses.mutate();
      builder.maxOutputTokens(tokens);
      return builder.build();
    }
    if (options instanceof OpenAiChatOptions chat && chat.getMaxCompletionTokens() != null)
      return withCompletionLimit(chat, tokens);
    return options.mutate().maxTokens(tokens).build();
  }

  /** Read the accepted request's native output limit without changing its parameter family. */
  public static Integer outputLimit(ToolCallingChatOptions options) {
    if (options == null) return null;
    if (options instanceof OpenAiResponsesChatOptions responses)
      return responses.getMaxOutputTokens();
    else if (options instanceof OpenAiChatOptions chat) {
      var completion = chat.getMaxCompletionTokens();
      return completion == null ? chat.getMaxTokens() : completion;
    } else return options.getMaxTokens();
  }

  /** Bound a feature's requested output without raising the accepted provider limit. */
  public static ToolCallingChatOptions capOutputLimit(ToolCallingChatOptions options, int tokens) {
    Integer configured = outputLimit(options);
    return withOutputLimit(options, configured == null ? tokens : Math.min(configured, tokens));
  }

  /** Structured extractors explicitly use completion-token budgets on Chat Completions. */
  public static ToolCallingChatOptions withCompletionLimit(
      ToolCallingChatOptions options, int tokens) {
    if (tokens < 1) throw new IllegalArgumentException("Output limit must be positive");
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.maxTokens(null);
      builder.maxCompletionTokens(tokens);
      return builder.build();
    }
    return options instanceof OpenAiResponsesChatOptions
        ? capOutputLimit(options, tokens)
        : withOutputLimit(options, tokens);
  }

  /**
   * Grounding's legacy token parameter remains explicit while native requests retain their bound.
   */
  public static ToolCallingChatOptions withTokenLimit(ToolCallingChatOptions options, int tokens) {
    if (tokens < 1) throw new IllegalArgumentException("Output limit must be positive");
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.maxCompletionTokens(null);
      builder.maxTokens(tokens);
      return builder.build();
    }
    return capOutputLimit(options, tokens);
  }

  /** A protocol-specific feature setting must never silently disappear on another API. */
  public static ToolCallingChatOptions withFrequencyPenalty(
      ToolCallingChatOptions options, double penalty) {
    if (options instanceof OpenAiResponsesChatOptions)
      throw new IllegalArgumentException("Frequency penalty is unavailable for Responses");
    return options.mutate().frequencyPenalty(penalty).build();
  }

  /** Set an explicitly selected native tool-choice value, including clearing the choice. */
  public static ToolCallingChatOptions withToolChoice(
      ToolCallingChatOptions options, String choice) {
    if (options instanceof OpenAiResponsesChatOptions responses) {
      var builder = responses.mutate();
      builder.toolChoice(choice);
      return builder.build();
    }
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.toolChoice(choice);
      return builder.build();
    }
    throw new IllegalArgumentException("Tool choice is unavailable for this protocol");
  }

  /** Keep the SDK's structured-output type while selecting the request's native option family. */
  public static ToolCallingChatOptions withResponseFormat(
      ToolCallingChatOptions options,
      org.springframework.ai.openai.OpenAiChatModel.ResponseFormat format) {
    if (options instanceof OpenAiResponsesChatOptions responses) {
      var builder = responses.mutate();
      builder.responseFormat(format);
      return builder.build();
    }
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.responseFormat(format);
      return builder.build();
    }
    throw new IllegalArgumentException("Structured output is unavailable for this protocol");
  }

  /** Reject wire-level tools that could bypass the caller's explicit callback boundary. */
  public static void requireNoRawTools(ToolCallingChatOptions options) {
    java.util.Map<String,Object> extra=null;
    if(options instanceof OpenAiResponsesChatOptions responses) {
      extra=responses.getExtraBody();
      var hosted=responses.getHostedTools();
      if(hosted!=null && !hosted.isEmpty()) throw new IllegalArgumentException("Model must not carry hosted tools");
    } else if(options instanceof OpenAiChatOptions chat) extra=chat.getExtraBody();
    Object raw=extra==null ? null : extra.get("tools");
    if(raw!=null && !(raw instanceof java.util.Collection<?> tools && tools.isEmpty()))
      throw new IllegalArgumentException("Model must not carry raw default tools");
  }

  /** Rei owns retry admission; Responses retry suppression belongs to its bound SDK client. */
  public static ToolCallingChatOptions withoutRetries(ToolCallingChatOptions options) {
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.maxRetries(0);
      return builder.build();
    }
    return options;
  }

  /** A feature may shorten, but never raise, a bound Responses read deadline. */
  public static ToolCallingChatOptions withTimeout(
      ToolCallingChatOptions options, java.time.Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("Timeout must be positive");
    if (options instanceof OpenAiResponsesChatOptions responses) {
      var configured = responses.getTimeout();
      var builder = responses.mutate();
      builder.timeout(
          configured != null && configured.compareTo(timeout) < 0 ? configured : timeout);
      return builder.build();
    }
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.timeout(timeout);
      return builder.build();
    }
    throw new IllegalArgumentException("Request timeout is unavailable for this protocol");
  }

  /** Local chat-template controls have no implicit Responses equivalent. */
  public static ToolCallingChatOptions withLocalThinking(
      ToolCallingChatOptions options, Boolean thinking) {
    if (thinking == null) return options;
    if (!(options instanceof OpenAiChatOptions chat))
      throw new IllegalArgumentException(
          "Local thinking template override is unavailable for this protocol");
    var extra = new java.util.LinkedHashMap<String, Object>();
    var configuredExtra = chat.getExtraBody();
    if (configuredExtra != null) extra.putAll(configuredExtra);
    var template = new java.util.LinkedHashMap<String, Object>();
    var configured = extra.get("chat_template_kwargs");
    if (configured != null) {
      if (!(configured instanceof java.util.Map<?, ?> values))
        throw new IllegalArgumentException("chat_template_kwargs must be an object");
      values.forEach((key, value) -> {
        if (!(key instanceof String name))
          throw new IllegalArgumentException("chat_template_kwargs keys must be strings");
        template.put(name, value);
      });
    }
    template.put("enable_thinking", thinking);
    extra.put("chat_template_kwargs", template);
    var builder = chat.mutate();
    builder.extraBody(extra);
    return builder.build();
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
