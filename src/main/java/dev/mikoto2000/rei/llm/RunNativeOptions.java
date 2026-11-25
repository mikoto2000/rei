package dev.mikoto2000.rei.llm;

import java.util.Map;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Native request generation rebuilt from a credential-free accepted snapshot. */
final class RunNativeOptions {
  private RunNativeOptions() {}

  static Map<String, Object> generation(ToolCallingChatOptions options) {
    var values = new java.util.LinkedHashMap<String, Object>();
    put(values, "temperature", options.getTemperature());
    put(values, "top_p", options.getTopP());
    if (options instanceof OpenAiChatOptions chat) {
      var modalities = chat.getOutputModalities();
      if (modalities != null && !modalities.isEmpty()) throw unsupported("modalities");
      if (chat.getOutputAudio() != null) throw unsupported("audio");
      var extraBody = chat.getExtraBody();
      if (extraBody != null && !extraBody.isEmpty()) throw unsupported("extra_body");
      put(values, "tool_choice", chat.getToolChoice());
      put(values, "metadata", chat.getMetadata());
      put(values, "prompt_cache_key", chat.getPromptCacheKey());
      put(values, "user", chat.getUser());
      put(values, "replay_reasoning_content", chat.getReplayReasoningContent());
      put(values, "stream_options", streamSettings(chat.getStreamOptions()));
      put(values, "response_format", formatSettings(chat.getResponseFormat()));
      put(values, "max_tokens", chat.getMaxTokens());
      put(values, "max_completion_tokens", chat.getMaxCompletionTokens());
      put(values, "reasoning_effort", chat.getReasoningEffort());
      put(values, "service_tier", chat.getServiceTier());
      put(values, "frequency_penalty", chat.getFrequencyPenalty());
      put(values, "presence_penalty", chat.getPresencePenalty());
      put(values, "stop", chat.getStopSequences());
      put(values, "seed", chat.getSeed());
      put(values, "n", chat.getN());
      put(values, "logit_bias", chat.getLogitBias());
      put(values, "logprobs", chat.getLogprobs());
      put(values, "top_logprobs", chat.getTopLogprobs());
      put(values, "verbosity", chat.getVerbosity());
      put(values, "strict", chat.getStrict());
      put(values, "parallel_tool_calls", chat.getParallelToolCalls());
      put(values, "store", chat.getStore());
    } else if (options instanceof OpenAiResponsesChatOptions responses) {
      var hostedTools = responses.getHostedTools();
      if (hostedTools != null && !hostedTools.isEmpty()) throw unsupported("hosted_tools");
      var extraBody = responses.getExtraBody();
      if (extraBody != null && !extraBody.isEmpty()) throw unsupported("extra_body");
      put(values, "tool_choice", responses.getToolChoice());
      put(values, "metadata", responses.getMetadata());
      put(values, "prompt_cache_key", responses.getPromptCacheKey());
      put(values, "safety_identifier", responses.getSafetyIdentifier());
      put(values, "response_format", formatSettings(responses.getResponseFormat()));
      put(values, "max_output_tokens", responses.getMaxOutputTokens());
      put(values, "reasoning_effort", responses.getReasoningEffort());
      put(values, "service_tier", responses.getServiceTier());
      put(values, "reasoning_summary", responses.getReasoningSummary());
      put(values, "verbosity", responses.getVerbosity());
      put(values, "strict", responses.getStrict());
      put(values, "parallel_tool_calls", responses.getParallelToolCalls());
      put(values, "max_tool_calls", responses.getMaxToolCalls());
      put(values, "include", responses.getInclude());
      put(values, "truncation", responses.getTruncation());
    } else throw new IllegalArgumentException("Unknown native Run API");
    return RunGenerationSettings.copy(values);
  }

  private static void put(Map<String, Object> values, String key, Object value) {
    if (value != null) values.put(key, value);
  }

  static ToolCallingChatOptions options(RunLlmConnection connection) {
    var context = new java.util.HashMap<String, Object>();
    context.put(
        dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection);
    context.put(ResolvedLlmEndpoint.CONTEXT_KEY, connection.endpoint());
    if (connection.endpoint().capabilities().maxTools() != null)
      context.put(
          LlmCapabilities.MAX_TOOLS_CONTEXT_KEY, connection.endpoint().capabilities().maxTools());
    if (connection.endpoint().apiType() == LlmApiType.CHAT_COMPLETIONS) {
      var builder = OpenAiChatOptions.builder();
      builder.model(connection.endpoint().model());
      builder.maxRetries(0);
      builder.timeout(connection.requestTimeout());
      builder.toolContext(context);
      connection
          .generation()
          .forEach((name, value) -> {
            switch (name) {
              case "temperature" -> builder.temperature(number(name, value));
              case "top_p" -> builder.topP(number(name, value));
              case "max_tokens" -> builder.maxTokens(integer(name, value));
              case "max_completion_tokens" -> builder.maxCompletionTokens(integer(name, value));
              case "reasoning_effort" -> builder.reasoningEffort(string(name, value));
              case "service_tier" -> builder.serviceTier(string(name, value));
              case "frequency_penalty" -> builder.frequencyPenalty(number(name, value));
              case "presence_penalty" -> builder.presencePenalty(number(name, value));
              case "stop" -> builder.stop(strings(name, value));
              case "seed" -> builder.seed(signedInteger(name, value));
              case "n" -> builder.n(integer(name, value));
              case "logit_bias" -> builder.logitBias(integerMap(name, value));
              case "logprobs" -> builder.logprobs(bool(name, value));
              case "top_logprobs" -> builder.topLogprobs(signedInteger(name, value));
              case "verbosity" -> builder.verbosity(string(name, value));
              case "strict" -> builder.strict(bool(name, value));
              case "parallel_tool_calls" -> builder.parallelToolCalls(bool(name, value));
              case "store" -> builder.store(bool(name, value));
              case "response_format" -> builder.responseFormat(format(value));
              case "tool_choice" -> builder.toolChoice(toolChoice(value));
              case "metadata" -> builder.metadata(stringMap(name, value));
              case "prompt_cache_key" -> builder.promptCacheKey(string(name, value));
              case "user" -> builder.user(string(name, value));
              case "replay_reasoning_content" -> builder.replayReasoningContent(bool(name, value));
              case "stream_options" -> builder.streamOptions(streamOptions(value));
              default -> throw unsupported(name);
            }
          });
      return builder.build();
    }
    var builder = OpenAiResponsesChatOptions.builder();
    builder.model(connection.endpoint().model());
    builder.maxRetries(0);
    builder.timeout(connection.requestTimeout());
    builder.toolContext(context);
    connection
        .generation()
        .forEach((name, value) -> {
          switch (name) {
            case "temperature" -> builder.temperature(number(name, value));
            case "top_p" -> builder.topP(number(name, value));
            case "max_output_tokens" -> builder.maxOutputTokens(integer(name, value));
            case "reasoning_effort" -> builder.reasoningEffort(string(name, value));
            case "service_tier" -> builder.serviceTier(string(name, value));
            case "reasoning_summary" -> builder.reasoningSummary(string(name, value));
            case "verbosity" -> builder.verbosity(string(name, value));
            case "strict" -> builder.strict(bool(name, value));
            case "parallel_tool_calls" -> builder.parallelToolCalls(bool(name, value));
            case "max_tool_calls" -> builder.maxToolCalls(integer(name, value));
            case "include" -> builder.include(strings(name, value));
            case "truncation" -> builder.truncation(string(name, value));
            case "response_format" -> builder.responseFormat(format(value));
            case "tool_choice" -> builder.toolChoice(toolChoice(value));
            case "metadata" -> builder.metadata(stringMap(name, value));
            case "prompt_cache_key" -> builder.promptCacheKey(string(name, value));
            case "safety_identifier" -> builder.safetyIdentifier(string(name, value));
            default -> throw unsupported(name);
          }
        });
    return builder.build();
  }

  private static Double number(String name, Object value) {
    if (value instanceof Number number) return number.doubleValue();
    throw unsupported(name);
  }

  private static Object toolChoice(Object value) {
    if (value instanceof String || value instanceof Map<?, ?>) return value;
    throw unsupported("tool_choice");
  }

  private static Map<String, String> stringMap(String name, Object value) {
    if (!(value instanceof Map<?, ?> map)) throw unsupported(name);
    var copy = new java.util.LinkedHashMap<String, String>();
    map.forEach((key, item) -> copy.put(string(name, key), string(name, item)));
    return Map.copyOf(copy);
  }

  private static Map<String, Object> streamSettings(OpenAiChatOptions.StreamOptions stream) {
    if (stream == null) return null;
    var additional = stream.additionalProperties();
    if (additional != null && !additional.isEmpty()) throw unsupported("stream_options");
    var values = new java.util.LinkedHashMap<String, Object>();
    put(values, "include_usage", stream.includeUsage());
    put(values, "include_obfuscation", stream.includeObfuscation());
    return Map.copyOf(values);
  }

  private static OpenAiChatOptions.StreamOptions streamOptions(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw unsupported("stream_options");
    var builder = OpenAiChatOptions.StreamOptions.builder();
    map.forEach((name, flag) -> {
      if ("include_usage".equals(name)) builder.includeUsage(bool("stream_options", flag));
      else if ("include_obfuscation".equals(name))
        builder.includeObfuscation(bool("stream_options", flag));
      else throw unsupported("stream_options");
    });
    return builder.build();
  }

  private static Integer integer(String name, Object value) {
    if (value instanceof Integer number && number > 0) return number;
    throw unsupported(name);
  }

  private static String string(String name, Object value) {
    if (value instanceof String text) return text;
    throw unsupported(name);
  }

  private static Integer signedInteger(String name, Object value) {
    if (value instanceof Integer number) return number;
    throw unsupported(name);
  }

  private static Boolean bool(String name, Object value) {
    if (value instanceof Boolean flag) return flag;
    throw unsupported(name);
  }

  private static java.util.List<String> strings(String name, Object value) {
    if (value instanceof java.util.List<?> list) {
      var copy = new java.util.ArrayList<String>();
      for (var item : list) copy.add(string(name, item));
      return java.util.List.copyOf(copy);
    }
    throw unsupported(name);
  }

  private static Map<String, Integer> integerMap(String name, Object value) {
    if (!(value instanceof Map<?, ?> map)) throw unsupported(name);
    var copy = new java.util.LinkedHashMap<String, Integer>();
    map.forEach((key, item) -> copy.put(string(name, key), signedInteger(name, item)));
    return Map.copyOf(copy);
  }

  private static Map<String, Object> formatSettings(OpenAiChatModel.ResponseFormat format) {
    if (format == null) return null;
    if (format.getType() == null) throw unsupported("response_format");
    var values = new java.util.LinkedHashMap<String, Object>();
    values.put("type", format.getType().name().toLowerCase(java.util.Locale.ROOT));
    put(values, "json_schema", format.getJsonSchema());
    put(values, "strict", format.getStrict());
    return java.util.Collections.unmodifiableMap(values);
  }

  private static OpenAiChatModel.ResponseFormat format(Object value) {
    if (!(value instanceof Map<?, ?> values)) throw unsupported("response_format");
    if (!java.util.Set.of("type", "json_schema", "strict").containsAll(values.keySet()))
      throw unsupported("response_format");
    var type =
        switch (string("response_format", values.get("type"))) {
          case "text" -> OpenAiChatModel.ResponseFormat.Type.TEXT;
          case "json_object" -> OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT;
          case "json_schema" -> OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA;
          default -> throw unsupported("response_format");
        };
    var builder = OpenAiChatModel.ResponseFormat.builder().type(type);
    if (values.containsKey("json_schema")) {
      if (type != OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
        throw unsupported("response_format");
      builder.jsonSchema(string("response_format", values.get("json_schema")));
    } else if (type == OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
      throw unsupported("response_format");
    if (values.containsKey("strict")) builder.strict(bool("response_format", values.get("strict")));
    return builder.build();
  }

  private static IllegalArgumentException unsupported(String name) {
    return new IllegalArgumentException("Unsupported accepted Run setting: " + name);
  }
}
