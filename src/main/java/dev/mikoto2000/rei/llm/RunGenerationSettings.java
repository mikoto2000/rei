package dev.mikoto2000.rei.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable JSON generation values; transport credentials and runtime authority are excluded. */
final class RunGenerationSettings {
  private static final Set<String> FIELDS = Set.of(
      "max_tokens", "max_completion_tokens", "max_output_tokens", "temperature", "top_p", "top_k",
      "frequency_penalty", "presence_penalty", "stop", "seed", "n", "response_format",
      "logit_bias", "logprobs", "top_logprobs", "reasoning_effort", "reasoning_summary",
      "verbosity", "service_tier", "parallel_tool_calls", "max_tool_calls", "tool_choice",
      "strict", "store", "truncation", "include", "modalities", "audio", "replay_reasoning_content");

  private RunGenerationSettings() {}

  static Map<String, Object> copy(Map<String, Object> settings) {
    var copy = new LinkedHashMap<String, Object>();
    settings.forEach((name, value) -> {
      if (!FIELDS.contains(name)) throw invalid();
      copy.put(name, freeze(value, 0));
    });
    return Collections.unmodifiableMap(copy);
  }

  private static Object freeze(Object value, int depth) {
    if (depth > 64) throw invalid();
    if (value == null || value instanceof String || value instanceof Boolean
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
