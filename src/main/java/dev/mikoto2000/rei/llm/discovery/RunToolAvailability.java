package dev.mikoto2000.rei.llm.discovery;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Run-local visibility. Business retention is a projection of existing ledgers, not another status.
 */
public final class RunToolAvailability {
  public record Saved(Map<String, Long> active, long clock, long version) {
    public Saved {
      active = Map.copyOf(active);
      if (clock < 0 || version < 0 || active.values().stream().anyMatch(v -> v < 0 || v > clock))
        throw new IllegalArgumentException("Invalid tool visibility revision");
    }
  }

  public record Change(
      String status,
      List<String> added,
      List<String> removed,
      long toolsetVersion,
      int activeToolCount,
      int maxToolCount,
      int requiredToolCount,
      List<String> protectedTools) {
    public Change {
      added = List.copyOf(added);
      removed = List.copyOf(removed);
      protectedTools = List.copyOf(protectedTools);
    }
  }

  private final ToolCatalog catalog;
  private final int maximum;
  private final int perSearch;
  private Map<String, Long> active = new TreeMap<>();
  private final Map<String, Integer> executing = new HashMap<>();
  private Supplier<Set<String>> retention = Set::of;
  private long clock;
  private long version;

  public RunToolAvailability(ToolCatalog catalog, int maximum, int perSearch) {
    this.catalog = catalog;
    this.maximum = maximum;
    this.perSearch = perSearch;
    if (maximum < catalog.basicNames().size() + 1 || perSearch < 1)
      throw new IllegalArgumentException(
          "Tool limit cannot accommodate the basic tools and searchTools");
  }

  public synchronized void setRetention(Supplier<Set<String>> retention) {
    this.retention = Objects.requireNonNull(retention);
  }

  public synchronized Saved saved() {
    return new Saved(active, clock, version);
  }

  public synchronized void restore(Saved saved) {
    var copy = new TreeMap<>(saved.active());
    catalog.closure(copy.keySet());
    if (names(copy.keySet()).size() > maximum)
      throw new IllegalStateException("Saved tool set exceeds endpoint limit");
    active = copy;
    clock = saved.clock();
    version = saved.version();
  }

  public synchronized Set<String> names() {
    return names(active.keySet());
  }

  private Set<String> names(Set<String> roots) {
    var names = new TreeSet<>(catalog.basicNames());
    names.add("searchTools");
    names.addAll(catalog.toolsForSets(roots));
    return Collections.unmodifiableSet(names);
  }

  public synchronized Change add(Set<String> requested) {
    final Set<String> retained;
    try {
      retained = Set.copyOf(retention.get());
    } catch (RuntimeException error) {
      return failure("retention_unavailable");
    }
    final Set<String> closure;
    try {
      closure = catalog.closure(requested);
    } catch (IllegalArgumentException unavailable) {
      return failure("unavailable");
    }
    Set<String> before = names();
    var additions = new TreeSet<>(catalog.toolsForSets(closure));
    additions.removeAll(before);
    if (additions.size() > perSearch) return failure("selection_too_large");
    var next = new TreeMap<>(active);
    long now = clock + 1;
    for (String id : requested) next.put(id, now);
    var protectedNames = new HashSet<>(retained);
    protectedNames.addAll(executing.keySet());
    // Requested roots and their dependencies cannot be evicted in their own transaction.
    protectedNames.addAll(catalog.toolsForSets(closure));
    var candidates =
        active.keySet().stream()
            .sorted(
                Comparator.<String>comparingLong(active::get)
                    .thenComparing(Comparator.naturalOrder()))
            .toList();
    for (String id : candidates) {
      if (names(next.keySet()).size() <= maximum) break;
      if (requested.contains(id)) continue;
      var attempt = new TreeMap<>(next);
      attempt.remove(id);
      if (names(attempt.keySet()).containsAll(protectedNames)) next = attempt;
    }
    Set<String> after = names(next.keySet());
    if (after.size() > maximum || !after.containsAll(retained))
      return new Change(
          "capacity_exceeded",
          List.of(),
          List.of(),
          version,
          before.size(),
          maximum,
          after.size(),
          protectedNames.stream().sorted().toList());
    var added = new TreeSet<>(after);
    added.removeAll(before);
    var removed = new TreeSet<>(before);
    removed.removeAll(after);
    active = next;
    clock = now;
    if (!before.equals(after)) version++;
    return new Change(
        added.isEmpty() ? "already_available" : "added",
        List.copyOf(added),
        List.copyOf(removed),
        version,
        after.size(),
        maximum,
        after.size(),
        List.of());
  }

  private Change failure(String status) {
    return new Change(
        status, List.of(), List.of(), version, names().size(), maximum, names().size(), List.of());
  }

  public synchronized Protection protect(Set<String> toolNames) {
    for (String name : toolNames) {
      if (!names().contains(name)) throw new IllegalArgumentException("Unpublished tool: " + name);
      executing.merge(name, 1, Integer::sum);
      if (catalog.names().contains(name) && !catalog.basicNames().contains(name)) {
        String set = catalog.operationSetFor(name);
        long accessed = ++clock;
        for (String id : List.copyOf(active.keySet()))
          if (catalog.closure(Set.of(id)).contains(set)) active.put(id, accessed);
      }
    }
    return new Protection(toolNames);
  }

  public final class Protection implements AutoCloseable {
    private final Set<String> names;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();

    private Protection(Set<String> names) {
      this.names = Set.copyOf(names);
    }

    public void close() {
      if (closed.compareAndSet(false, true)) release(names);
    }
  }

  private synchronized void release(Set<String> names) {
    names.forEach(n -> executing.compute(n, (k, v) -> v == 1 ? null : v - 1));
  }
}
