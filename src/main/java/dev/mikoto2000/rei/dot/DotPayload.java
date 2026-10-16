package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.event.CredentialRedactor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class DotPayload {
  private DotPayload() {}

  public static String text(Map<String, Object> a, String key, boolean required, int max) {
    Object v = a.get(key);
    if (v == null && !required) return "";
    if (!(v instanceof String s)
        || required && s.isBlank()
        || s.getBytes(StandardCharsets.UTF_8).length > max)
      throw new DotException("INVALID_ARGUMENT");
    return s;
  }

  public static int integer(Object value, int fallback, int min, int max) {
    if (value == null) return fallback;
    if (!(value instanceof Number n)
        || n.doubleValue() != n.longValue()
        || n.longValue() < min
        || n.longValue() > max) throw new DotException("INVALID_ARGUMENT");
    return n.intValue();
  }

  public static String safe(String value) {
    return CredentialRedactor.redact(value)
        .replaceAll("\\\\\\\\[^\\s\\\"<>]+", "[LOCAL_PATH]")
        .replaceAll("(?i)[A-Z]:[\\\\/][^\\s\\\"<>]+", "[LOCAL_PATH]")
        .replaceAll("(?<![:/\\w])/(?:[^\\s\\\"<>]+)", "[LOCAL_PATH]");
  }

  private static String boundedSafe(String value, int bytes) {
    String safe = safe(value);
    if (safe.getBytes(StandardCharsets.UTF_8).length > bytes)
      throw new DotException("INVALID_ARGUMENT");
    return safe;
  }

  public static List<String> strings(
      Map<String, Object> a, String key, int maxCount, int maxBytes) {
    if (!a.containsKey(key) || a.get(key) == null) return List.of();
    if (!(a.get(key) instanceof List<?> list) || list.size() > maxCount)
      throw new DotException("INVALID_ARGUMENT");
    var result = new ArrayList<String>();
    for (Object v : list) {
      if (!(v instanceof String s)
          || s.isBlank()
          || s.getBytes(StandardCharsets.UTF_8).length > maxBytes)
        throw new DotException("INVALID_ARGUMENT");
      result.add(boundedSafe(s, maxBytes));
    }
    return List.copyOf(result);
  }

  public static String id(Map<String, Object> a, String key) {
    String id = text(a, key, true, 100);
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw new DotException("INVALID_ARGUMENT");
    }
  }

  public static Map<String, Object> publication(Map<String, Object> a) {
    keys(
        a,
        Set.of(
            "category",
            "instruction",
            "context_summary",
            "expected_output",
            "constraints",
            "acceptance_criteria",
            "timeout"));
    String category = text(a, "category", false, 40);
    if (category.isEmpty()) category = "TECH_RESEARCH";
    if (!Set.of("TECH_RESEARCH", "COMPARISON", "REVIEW").contains(category))
      throw new DotException("CATEGORY_NOT_ALLOWED");
    var out = new LinkedHashMap<String, Object>();
    out.put("category", category);
    out.put("execution_scope", "RESEARCH_ONLY");
    out.put("instruction", boundedSafe(text(a, "instruction", true, 16384), 16384));
    out.put("context_summary", boundedSafe(text(a, "context_summary", false, 8192), 8192));
    out.put("expected_output", boundedSafe(text(a, "expected_output", false, 4096), 4096));
    out.put("constraints", strings(a, "constraints", 16, 2048));
    out.put("acceptance_criteria", strings(a, "acceptance_criteria", 16, 2048));
    return out;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> result(Map<String, Object> a, List<?> criteria) {
    keys(
        a,
        Set.of(
            "request_id",
            "claim_id",
            "submission_id",
            "outcome",
            "summary",
            "content",
            "evidence",
            "unresolved_items",
            "acceptance_results"));
    String outcome = text(a, "outcome", true, 40);
    if (!Set.of("COMPLETED", "PARTIAL", "FAILED", "UNKNOWN").contains(outcome))
      throw new DotException("INVALID_ARGUMENT");
    var out = new LinkedHashMap<String, Object>();
    out.put("outcome", outcome);
    out.put("summary", boundedSafe(text(a, "summary", true, 4096), 4096));
    out.put("content", boundedSafe(text(a, "content", false, 65536), 65536));
    out.put("unresolved_items", strings(a, "unresolved_items", 20, 2048));
    Object raw = a.getOrDefault("evidence", List.of());
    if (!(raw instanceof List<?> evidence) || evidence.size() > 20)
      throw new DotException("INVALID_ARGUMENT");
    var facts = new ArrayList<Map<String, Object>>();
    for (Object v : evidence) {
      if (!(v instanceof Map<?, ?>)) throw new DotException("INVALID_ARGUMENT");
      var fact = (Map<String, Object>) v;
      keys(fact, Set.of("title", "url", "description"));
      String url = text(fact, "url", true, 2048);
      URI uri;
      try {
        uri = URI.create(url);
      } catch (IllegalArgumentException e) {
        throw new DotException("INVALID_ARGUMENT");
      }
      if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), ""))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || !CredentialRedactor.redact(url).equals(url))
        throw new DotException("INVALID_ARGUMENT");
      facts.add(
          Map.of(
              "title",
              boundedSafe(text(fact, "title", true, 1024), 1024),
              "url",
              url,
              "description",
              boundedSafe(text(fact, "description", false, 2048), 2048)));
    }
    out.put("evidence", facts);
    Object checks = a.getOrDefault("acceptance_results", List.of());
    if (!(checks instanceof List<?> results) || results.size() > 16)
      throw new DotException("INVALID_ARGUMENT");
    var normalized = new ArrayList<Map<String, Object>>();
    var seen = new HashSet<Integer>();
    for (Object v : results) {
      if (!(v instanceof Map<?, ?>)) throw new DotException("INVALID_ARGUMENT");
      var check = (Map<String, Object>) v;
      keys(check, Set.of("criterion_index", "status", "evidence_indices"));
      int index = integer(check.get("criterion_index"), -1, 0, criteria.size() - 1);
      if (index < 0 || !seen.add(index)) throw new DotException("INVALID_ARGUMENT");
      String state = text(check, "status", true, 30);
      if (!Set.of("SATISFIED", "UNSATISFIED", "UNKNOWN").contains(state))
        throw new DotException("INVALID_ARGUMENT");
      Object refs = check.getOrDefault("evidence_indices", List.of());
      if (!(refs instanceof List<?> indices) || indices.size() > 20)
        throw new DotException("INVALID_ARGUMENT");
      var indicesOut = new ArrayList<Integer>();
      for (Object i : indices) {
        int n = integer(i, -1, 0, facts.size() - 1);
        if (n < 0 || indicesOut.contains(n)) throw new DotException("INVALID_ARGUMENT");
        indicesOut.add(n);
      }
      normalized.add(
          Map.of("criterion_index", index, "status", state, "evidence_indices", indicesOut));
    }
    out.put("acceptance_results", normalized);
    return out;
  }

  public static void keys(Map<String, Object> a, Set<String> keys) {
    if (a == null || !keys.containsAll(a.keySet())) throw new DotException("INVALID_ARGUMENT");
  }
}
