package dev.mikoto2000.rei.tooldelivery;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, optional application counters. Token values are estimates, never provider usage. */
public final class ReadingMetrics {
  private static final Map<ReadingRun, Map<String, Long>> RUNS = new LinkedHashMap<>();
  private static final Map<ReadingRun, java.util.Set<String>> BODIES = new LinkedHashMap<>();
  private static final Map<ReadingRun, java.util.Set<String>> WIRE_BODIES = new LinkedHashMap<>();

  private ReadingMetrics() {}

  public record Saved(
      Map<String, Long> values, java.util.Set<String> bodies, java.util.Set<String> wireBodies) {
    public Saved {
      values = Map.copyOf(values);
      bodies = java.util.Set.copyOf(bodies);
      wireBodies = wireBodies == null ? java.util.Set.of() : java.util.Set.copyOf(wireBodies);
    }

    public Saved(Map<String, Long> values, java.util.Set<String> bodies) {
      this(values, bodies, java.util.Set.of());
    }
  }

  private static final Object LOCK = new Object();

  private static boolean enabled() {
    return Boolean.parseBoolean(System.getProperty("rei.reading.metrics.enabled", "true"));
  }

  public static void restore(ReadingRun owner, ReadingStateStore store) {
    synchronized (LOCK) {
      if (!enabled() || owner == null || store == null || RUNS.containsKey(owner)) return;
      try {
        var saved = store.load(owner, "metrics", "totals", Saved.class);
        RUNS.put(owner, new LinkedHashMap<>(saved.values()));
        BODIES.put(owner, new java.util.HashSet<>(saved.bodies()));
        WIRE_BODIES.put(owner, new java.util.HashSet<>(saved.wireBodies()));
      } catch (RuntimeException unavailable) {
        /* No prior observations is normal for a new run. */
      }
    }
  }

  public static void flush(ReadingRun owner, ReadingStateStore store) {
    synchronized (LOCK) {
      if (!enabled() || owner == null || store == null) return;
      try {
        store.save(
            owner,
            "metrics",
            "totals",
            new Saved(
                snapshot(owner),
                java.util.Set.copyOf(BODIES.getOrDefault(owner, java.util.Set.of())),
                java.util.Set.copyOf(WIRE_BODIES.getOrDefault(owner, java.util.Set.of()))),
            java.time.Duration.ofDays(7));
      } catch (RuntimeException unavailable) {
        add(owner, "metricsPersistenceFailures", 1);
      }
    }
  }

  public static void add(String name, long amount) {
    add(ReadingRuntime.current(), name, amount);
  }

  public static void add(ReadingRun owner, String name, long amount) {
    synchronized (LOCK) {
      if (owner == null || !enabled()) return;
      var values = RUNS.computeIfAbsent(owner, unused -> new LinkedHashMap<>());
      values.merge(name, amount, Long::sum);
      while (RUNS.size() > 256) RUNS.remove(RUNS.keySet().iterator().next());
    }
  }

  public static void set(String name, long value) {
    synchronized (LOCK) {
      var owner = ReadingRuntime.current();
      if (owner == null || !enabled()) return;
      RUNS.computeIfAbsent(owner, unused -> new LinkedHashMap<>()).put(name, value);
      while (RUNS.size() > 256) RUNS.remove(RUNS.keySet().iterator().next());
    }
  }

  public static Map<String, Long> snapshot(ReadingRun owner) {
    synchronized (LOCK) {
      return Map.copyOf(RUNS.getOrDefault(owner, Map.of()));
    }
  }

  public static <T> T generated(T value) throws java.io.IOException {
    if (!enabled() || ReadingRuntime.current() == null) return value;
    byte[] bytes = ReadingJson.JSON.writeValueAsBytes(value);
    add("generatedBytes", bytes.length);
    add(
        "generatedEstimatedTokens",
        ReadingRuntime.estimateTokens(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
    observe(ReadingJson.JSON.valueToTree(value), "", "", false);
    return value;
  }

  public static void wire(Object value) throws java.io.IOException {
    if (!enabled() || ReadingRuntime.current() == null) return;
    String json = ReadingJson.JSON.writeValueAsString(value);
    add("wireBytes", json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    add("wireEstimatedTokens", ReadingRuntime.estimateTokens(json));
    observe(ReadingJson.JSON.valueToTree(value), "", "", true);
  }

  private static void observe(
      com.fasterxml.jackson.databind.JsonNode node, String path, String version, boolean wire) {
    if (node.isArray()) {
      for (var item : node) observe(item, path, version, wire);
      return;
    }
    if (!node.isObject()) return;
    path = node.path("path").asText(path);
    version = node.path("version").asText(version);
    var content = node.path("content");
    int line = node.path("startLine").asInt(node.path("line").asInt(1));
    if (!path.isEmpty()) {
      if (content.isTextual()) body(path, version, line, content.asText(), wire);
      else if (content.isArray())
        for (var text : content) body(path, version, line++, text.asText(), wire);
    }
    if (!wire && node.path("truncated").asBoolean()) add("truncatedResults", 1);
    if (!wire && node.hasNonNull("truncationReason"))
      add("stop." + node.path("truncationReason").asText(), 1);
    for (String field : java.util.List.of("matches", "sections", "files"))
      observe(node.path(field), path, version, wire);
  }

  private static void body(String path, String version, int line, String text, boolean wire) {
    synchronized (LOCK) {
      var owner = ReadingRuntime.current();
      var bodies = wire ? WIRE_BODIES : BODIES;
      var seen = bodies.computeIfAbsent(owner, unused -> new java.util.HashSet<>());
      String key =
          ReadingJson.hash(
              (path + "\n" + version + "\n" + line + "\n" + text)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (seen.contains(key))
        add(
            wire ? "wireDuplicateBodyBytes" : "duplicateBodyBytes",
            text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      else if (seen.size() < 1024) seen.add(key);
      else add(wire ? "wireDuplicateMeasurementUnknown" : "duplicateMeasurementUnknown", 1);
      while (bodies.size() > 256) bodies.remove(bodies.keySet().iterator().next());
    }
  }

  public static Operation operation(String kind) {
    return operation(kind, null);
  }

  public static Operation operation(String kind, ReadingStateStore store) {
    var owner = ReadingRuntime.current();
    restore(owner, store);
    add("toolCalls", 1);
    return new Operation(kind, System.nanoTime(), owner, store);
  }

  public record Operation(String kind, long started, ReadingRun owner, ReadingStateStore store)
      implements AutoCloseable {
    @Override
    public void close() {
      add(owner, kind + "Nanos", System.nanoTime() - started);
      flush(owner, store);
    }
  }
}
