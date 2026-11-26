package dev.mikoto2000.rei.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable JSON generation values; transport credentials and runtime authority are excluded. */
final class RunGenerationSettings {
  private static final Set<String> FIELDS =
      Set.of(
          "max_tokens",
          "max_completion_tokens",
          "max_output_tokens",
          "temperature",
          "top_p",
          "top_k",
          "frequency_penalty",
          "presence_penalty",
          "stop",
          "seed",
          "n",
          "response_format",
          "logit_bias",
          "logprobs",
          "top_logprobs",
          "reasoning_effort",
          "reasoning_summary",
          "verbosity",
          "service_tier",
          "parallel_tool_calls",
          "max_tool_calls",
          "tool_choice",
          "metadata",
          "prompt_cache_key",
          "user",
          "safety_identifier",
          "stream_options",
          "strict",
          "store",
          "truncation",
          "include",
          "modalities",
          "audio",
          "replay_reasoning_content");

  private RunGenerationSettings() {}

  static Map<String, Object> copy(Map<String, Object> settings) {
    var copy = new LinkedHashMap<String, Object>();
    settings.forEach((name, value) -> {
      if (!FIELDS.contains(name)) throw invalid();
      copy.put(name, freeze(value, 0));
    });
    return Collections.unmodifiableMap(copy);
  }

  static Map<String, Object> configuredCopy(Map<String, Object> settings) {
    if (settings == null) return Map.of();
    if (settings.size() > 32) throw configuredInvalid();
    var values = new LinkedHashMap<String, Object>();
    for (var entry : settings.entrySet()) {
      String key = entry.getKey();
      if (key == null || !key.matches("[a-z][a-z0-9_-]{0,63}")) throw configuredInvalid();
      String name = key.replace('-', '_');
      if (!FIELDS.contains(name) || values.containsKey(name)) throw configuredInvalid();
      Object value = entry.getValue();
      if (name.equals("response_format") && value instanceof Map<?, ?> format) {
        var normalized = new LinkedHashMap<String, Object>();
        for (var field : format.entrySet()) {
          if (!(field.getKey() instanceof String nested)) throw configuredInvalid();
          String normalizedKey = nested.replace('-', '_');
          if (!Set.of("type", "json_schema", "strict").contains(normalizedKey)
              || normalized.containsKey(normalizedKey)) throw configuredInvalid();
          normalized.put(
              normalizedKey,
              normalizedKey.equals("strict")
                  ? configuredBoolean(field.getValue())
                  : field.getValue());
        }
        value = normalized;
      }
      values.put(name, value);
    }
    return copy(values);
  }

  static Map<String, Object> named(
      Map<String, Object> defaults,
      Map<String, Object> configured,
      ResolvedLlmEndpoint endpoint,
      Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> capabilities) {
    var values = new LinkedHashMap<>(defaults);
    if (configured.containsKey("max_tokens") && configured.containsKey("max_completion_tokens"))
      throw configuredInvalid();
    for (var entry : configured.entrySet()) {
      String name = entry.getKey();
      var required =
          switch (name) {
            case "temperature" -> LlmEndpointCapabilities.Capability.TEMPERATURE;
            case "top_p" -> LlmEndpointCapabilities.Capability.TOP_P;
            case "reasoning_effort" -> LlmEndpointCapabilities.Capability.REASONING_EFFORT;
            case "reasoning_summary" -> LlmEndpointCapabilities.Capability.REASONING_SUMMARY;
            case "verbosity" -> LlmEndpointCapabilities.Capability.TEXT_VERBOSITY;
            case "parallel_tool_calls" -> LlmEndpointCapabilities.Capability.PARALLEL_TOOL_CALLS;
            case "response_format" -> LlmEndpointCapabilities.Capability.STRUCTURED_OUTPUT;
            case "max_tokens", "max_completion_tokens", "max_output_tokens" ->
                LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS;
            default ->
                throw new IllegalArgumentException("Unsupported named generation setting: " + name);
          };
      if (capabilities.get(required) != LlmEndpointCapabilities.Support.SUPPORTED)
        throw new IllegalStateException(
            "Named generation setting is unsupported or unconfirmed: " + name);
      boolean chat = endpoint.apiType() == LlmApiType.CHAT_COMPLETIONS;
      if (chat && Set.of("max_output_tokens", "reasoning_summary").contains(name)
          || !chat && Set.of("max_tokens", "max_completion_tokens").contains(name))
        throw new IllegalArgumentException(
            "Named generation setting does not match the API: " + name);
      Object value = entry.getValue();
      if (Set.of("temperature", "top_p").contains(name)) {
        double number = configuredNumber(value);
        if (number < 0 || number > (name.equals("top_p") ? 1 : 2)) throw configuredInvalid();
        value = number;
      } else if (name.startsWith("max_")) {
        double number = configuredNumber(value);
        var bound = endpoint.maxOutputTokens();
        if (bound == null || number < 1 || number > bound || number != Math.rint(number))
          throw new IllegalArgumentException(
              "Named generation token limit exceeds the accepted bound: " + name);
        value = (int) number;
        if (name.equals("max_completion_tokens")) values.remove("max_tokens");
        if (name.equals("max_tokens")) values.remove("max_completion_tokens");
      } else if (name.equals("parallel_tool_calls")) value = configuredBoolean(value);
      else if (name.equals("reasoning_effort"))
        requireChoice(value, Set.of("none", "minimal", "low", "medium", "high", "xhigh"));
      else if (name.equals("reasoning_summary"))
        requireChoice(value, Set.of("auto", "concise", "detailed"));
      else if (name.equals("verbosity")) requireChoice(value, Set.of("low", "medium", "high"));
      values.put(name, value);
    }
    return copy(values);
  }

  private static void requireChoice(Object value, Set<String> choices) {
    if (!(value instanceof String text) || !choices.contains(text)) throw configuredInvalid();
  }

  private static boolean configuredBoolean(Object value) {
    if (value instanceof Boolean flag) return flag;
    if ("true".equals(value)) return true;
    if ("false".equals(value)) return false;
    throw configuredInvalid();
  }

  private static double configuredNumber(Object value) {
    try {
      double number =
          value instanceof Number numeric
              ? numeric.doubleValue()
              : value instanceof String text ? Double.parseDouble(text) : Double.NaN;
      if (!Double.isFinite(number)) throw configuredInvalid();
      return number;
    } catch (NumberFormatException invalid) {
      throw configuredInvalid();
    }
  }

  private static IllegalArgumentException configuredInvalid() {
    return new IllegalArgumentException("Invalid named chat generation configuration");
  }

  private static Object freeze(Object value, int depth) {
    if (depth > 64) throw invalid();
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Integer) return value;
    if (value instanceof Byte || value instanceof Short) return ((Number) value).intValue();
    if (value instanceof Long number) {
      if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return number.intValue();
      return number;
    }
    if (value instanceof Double number && Double.isFinite(number)) return number;
    if (value instanceof Float number && Float.isFinite(number)) return number.doubleValue();
    if (value instanceof Map<?, ?> values) {
      var copy = new LinkedHashMap<String, Object>();
      values.forEach((key, item) -> {
        if (!(key instanceof String name)) throw invalid();
        copy.put(name, freeze(item, depth + 1));
      });
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> values) {
      var copy = new java.util.ArrayList<Object>(values.size());
      for (var item : values) copy.add(freeze(item, depth + 1));
      return Collections.unmodifiableList(copy);
    }
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid credential-free Run generation settings");
  }
}
