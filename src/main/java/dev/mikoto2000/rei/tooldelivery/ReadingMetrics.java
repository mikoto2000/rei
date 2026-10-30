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
  // Bounded lock storage; a run always uses the same persistence stripe.
  private static final Object[] PERSISTENCE =
      java.util.stream.IntStream.range(0, 64).mapToObj(unused -> new Object()).toArray();
  private static final ThreadLocal<Map<ReadingRun, Pending>> OPERATIONS = new ThreadLocal<>();

  private static final class Pending {
    int depth;
    ReadingStateStore store;
  }

  private static Object persistenceLock(ReadingRun owner) {
    return PERSISTENCE[Math.floorMod(owner.hashCode(), PERSISTENCE.length)];
  }

  private static boolean enabled() {
    return Boolean.parseBoolean(System.getProperty("rei.reading.metrics.enabled", "true"));
  }

  public static void restore(ReadingRun owner, ReadingStateStore store) {
    if (!enabled() || owner == null || store == null) return;
    synchronized (persistenceLock(owner)) {
      synchronized (LOCK) {
        if (RUNS.containsKey(owner)) return;
      }
      try {
        var saved = store.load(owner, "metrics", "totals", Saved.class);
        synchronized (LOCK) {
          // A concurrently initialized run owns its current counters; never overwrite them.
          if (RUNS.containsKey(owner)) return;
          RUNS.put(owner, new LinkedHashMap<>(saved.values()));
          BODIES.put(owner, new java.util.HashSet<>(saved.bodies()));
          WIRE_BODIES.put(owner, new java.util.HashSet<>(saved.wireBodies()));
        }
      } catch (RuntimeException unavailable) {
        /* No prior observations is normal for a new run. */
      }
    }
  }

  public static void flush(ReadingRun owner, ReadingStateStore store) {
    if (!enabled() || owner == null || store == null) return;
    synchronized (persistenceLock(owner)) {
      Saved saved;
      synchronized (LOCK) {
        saved =
            new Saved(
                RUNS.getOrDefault(owner, Map.of()),
                BODIES.getOrDefault(owner, java.util.Set.of()),
                WIRE_BODIES.getOrDefault(owner, java.util.Set.of()));
      }
      try {
        store.save(owner, "metrics", "totals", saved, java.time.Duration.ofDays(7));
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
    var owner = ReadingRuntime.current();
    String key =
        ReadingJson.hash(
            (path + "\n" + version + "\n" + line + "\n" + text)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    synchronized (LOCK) {
      var bodies = wire ? WIRE_BODIES : BODIES;
      var seen = bodies.computeIfAbsent(owner, unused -> new java.util.HashSet<>());
      if (seen.contains(key)) add(wire ? "wireDuplicateBodyBytes" : "duplicateBodyBytes", bytes);
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
    if (owner != null && enabled()) {
      var operations = OPERATIONS.get();
      if (operations == null) {
        operations = new java.util.HashMap<>();
        OPERATIONS.set(operations);
      }
      var pending = operations.computeIfAbsent(owner, unused -> new Pending());
      pending.depth++;
      if (store != null) pending.store = store;
    }
    return new Operation(kind, System.nanoTime(), owner, store);
  }

  private static void finishOperation(ReadingRun owner, ReadingStateStore store) {
    var operations = OPERATIONS.get();
    var pending = operations == null ? null : operations.get(owner);
    if (pending == null) {
      flush(owner, store); // Preserve manually constructed Operation behavior.
      return;
    }
    if (--pending.depth > 0) return;
    operations.remove(owner);
    if (operations.isEmpty()) OPERATIONS.remove();
    flush(owner, pending.store);
  }

  public record Operation(String kind, long started, ReadingRun owner, ReadingStateStore store)
      implements AutoCloseable {
    @Override
    public void close() {
      add(owner, kind + "Nanos", System.nanoTime() - started);
      finishOperation(owner, store);
    }
  }
}
