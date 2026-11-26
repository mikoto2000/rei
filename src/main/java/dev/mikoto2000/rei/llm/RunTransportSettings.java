package dev.mikoto2000.rei.llm;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Frozen nonsecret connection attributes; no raw header or credential containers. */
final class RunTransportSettings {
  static final long DEFAULT_REQUEST_LIMIT_BYTES = 33_554_432;
  static final long DEFAULT_RESPONSE_LIMIT_BYTES = 67_108_864;
  static final long MAX_LIMIT_BYTES = 134_217_728;
  private static final Set<String> FIELDS =
      Set.of(
          "connect_timeout",
          "request_timeout",
          "organization_id",
          "project_id",
          "api_beta",
          "request_limit_bytes",
          "response_limit_bytes");

  private RunTransportSettings() {}

  static Map<String, String> copy(Map<String, String> values) {
    if (values == null) return Map.of();
    values.forEach((name, value) -> {
      if (!FIELDS.contains(name)
          || value == null
          || value.isBlank()
          || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
      if (name.equals("request_timeout") || name.equals("connect_timeout")) timeout(value);
      if (name.equals("request_limit_bytes") || name.equals("response_limit_bytes"))
        byteLimit(value);
    });
    return Map.copyOf(values);
  }

  static Map<String, String> defaults(Map<String, String> source) {
    var values = new java.util.HashMap<>(source);
    values.putIfAbsent("request_limit_bytes", Long.toString(DEFAULT_REQUEST_LIMIT_BYTES));
    values.putIfAbsent("response_limit_bytes", Long.toString(DEFAULT_RESPONSE_LIMIT_BYTES));
    values.putIfAbsent("connect_timeout", Duration.ofMinutes(1).toString());
    return copy(values);
  }

  static Map<String, String> profile(LlmProperties.Server source) {
    var values = new java.util.HashMap<String, String>();
    if (source.getRequestLimitBytes() != null)
      values.put("request_limit_bytes", source.getRequestLimitBytes().toString());
    if (source.getResponseLimitBytes() != null)
      values.put("response_limit_bytes", source.getResponseLimitBytes().toString());
    if (source.getConnectTimeout() != null)
      values.put("connect_timeout", source.getConnectTimeout().toString());
    if (source.getRequestTimeout() != null)
      values.put("request_timeout", source.getRequestTimeout().toString());
    return defaults(values);
  }

  static long byteLimit(String value) {
    try {
      long limit = Long.parseLong(value);
      if (limit < 1 || limit > MAX_LIMIT_BYTES) throw invalid();
      return limit;
    } catch (NumberFormatException error) {
      throw invalid();
    }
  }

  static long byteLimit(Map<String, String> values, String field, long fallback) {
    return values.containsKey(field) ? byteLimit(values.get(field)) : fallback;
  }

  static Duration timeout(String value) {
    try {
      var duration = Duration.parse(value);
      if (duration.isNegative() || duration.isZero()) throw invalid();
      return duration;
    } catch (java.time.DateTimeException error) {
      throw invalid();
    }
  }

  static Map<String, String> binding(Map<String, String> values) {
    var binding = new java.util.HashMap<>(values);
    binding.remove("request_timeout");
    binding.remove("connect_timeout");
    binding.remove("request_limit_bytes");
    binding.remove("response_limit_bytes");
    return Map.copyOf(binding);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid credential-free Run transport settings");
  }
}
