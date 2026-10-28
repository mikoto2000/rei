package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, optional application counters. Token values are estimates, never provider usage. */
public final class ReadingMetrics {
  private static final Map<AgentRunContext, Map<String, Long>> RUNS = new LinkedHashMap<>();
  private static final Map<AgentRunContext, java.util.Set<String>> BODIES = new LinkedHashMap<>();

  private ReadingMetrics() {}

  public record Saved(Map<String, Long> values, java.util.Set<String> bodies) {}

  private static boolean enabled() {
    return Boolean.parseBoolean(System.getProperty("rei.reading.metrics.enabled", "true"));
  }

  public static synchronized void restore(AgentRunContext owner, ReadingStateStore store) {
    if (!enabled() || owner == null || store == null || RUNS.containsKey(owner)) return;
    try {
      var saved = store.load(owner, "metrics", "totals", Saved.class);
      RUNS.put(owner, new LinkedHashMap<>(saved.values()));
      BODIES.put(owner, new java.util.HashSet<>(saved.bodies()));
    } catch (RuntimeException unavailable) {
      /* No prior observations is normal for a new run. */
    }
  }

  public static synchronized void flush(AgentRunContext owner, ReadingStateStore store) {
    if (!enabled() || owner == null || store == null) return;
    try {
      store.save(
          owner,
          "metrics",
          "totals",
          new Saved(
              snapshot(owner),
              java.util.Set.copyOf(BODIES.getOrDefault(owner, java.util.Set.of()))),
          java.time.Duration.ofDays(7));
    } catch (RuntimeException unavailable) {
      add(owner, "metricsPersistenceFailures", 1);
    }
  }

  public static void add(String name, long amount) {
    add(AgentRunScope.current(), name, amount);
  }

  public static synchronized void add(AgentRunContext owner, String name, long amount) {
    if (owner == null || !enabled()) return;
    var values = RUNS.computeIfAbsent(owner, unused -> new LinkedHashMap<>());
    values.merge(name, amount, Long::sum);
    while (RUNS.size() > 256) RUNS.remove(RUNS.keySet().iterator().next());
  }

  public static synchronized Map<String, Long> snapshot(AgentRunContext owner) {
    return Map.copyOf(RUNS.getOrDefault(owner, Map.of()));
  }

  static <T> T generated(T value) throws java.io.IOException {
    if (!enabled() || AgentRunScope.current() == null) return value;
    byte[] bytes = dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsBytes(value);
    add("generatedBytes", bytes.length);
    add(
        "generatedEstimatedTokens",
        TokenEstimator.conservative()
            .text(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
    observe(dev.mikoto2000.rei.storage.StorageDatabase.JSON.valueToTree(value), "", "");
    return value;
  }

  private static void observe(
      com.fasterxml.jackson.databind.JsonNode node, String path, String version) {
    if (node.isArray()) {
      for (var item : node) observe(item, path, version);
      return;
    }
    if (!node.isObject()) return;
    path = node.path("path").asText(path);
    version = node.path("version").asText(version);
    var content = node.path("content");
    int line = node.path("startLine").asInt(node.path("line").asInt(1));
    if (!path.isEmpty()) {
      if (content.isTextual()) body(path, version, line, content.asText());
      else if (content.isArray())
        for (var text : content) body(path, version, line++, text.asText());
    }
    if (node.path("truncated").asBoolean()) add("truncatedResults", 1);
    if (node.hasNonNull("truncationReason"))
      add("stop." + node.path("truncationReason").asText(), 1);
    for (String field : java.util.List.of("matches", "sections"))
      observe(node.path(field), path, version);
  }

  private static synchronized void body(String path, String version, int line, String text) {
    var owner = AgentRunScope.current();
    var seen = BODIES.computeIfAbsent(owner, unused -> new java.util.HashSet<>());
    String key =
        FileSnapshots.hash(
            (path + "\n" + version + "\n" + line + "\n" + text)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (seen.contains(key))
      add("duplicateBodyBytes", text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    else if (seen.size() < 1024) seen.add(key);
    else add("duplicateMeasurementUnknown", 1);
    while (BODIES.size() > 256) BODIES.remove(BODIES.keySet().iterator().next());
  }

  static Operation operation(String kind) {
    return operation(kind, null);
  }

  static Operation operation(String kind, ReadingStateStore store) {
    var owner = AgentRunScope.current();
    restore(owner, store);
    add("toolCalls", 1);
    return new Operation(kind, System.nanoTime(), owner, store);
  }

  record Operation(String kind, long started, AgentRunContext owner, ReadingStateStore store)
      implements AutoCloseable {
    @Override
    public void close() {
      add(owner, kind + "Nanos", System.nanoTime() - started);
      flush(owner, store);
    }
  }
}
