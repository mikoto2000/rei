package dev.mikoto2000.rei.llm;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Frozen nonsecret connection attributes; no raw header or credential containers. */
final class RunTransportSettings {
  private static final Set<String> FIELDS = Set.of(
      "request_timeout", "organization_id", "project_id", "api_beta");

  private RunTransportSettings() {}

  static Map<String, String> copy(Map<String, String> values) {
    if (values == null) return Map.of();
    values.forEach((name, value) -> {
      if (!FIELDS.contains(name) || value == null || value.isBlank()
          || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
      if (name.equals("request_timeout")) timeout(value);
    });
    return Map.copyOf(values);
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
    return Map.copyOf(binding);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid credential-free Run transport settings");
  }
}
