package dev.mikoto2000.rei.llm.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Immutable identities and explicit operation sets; never an execution resolver. */
public final class ToolCatalog {
  public record Entry(
      String name, boolean basic, String set, String aliases, List<String> dependencies) {
    public Entry {
      dependencies = List.copyOf(dependencies);
    }
  }

  private final Map<String, ToolCallback> callbacks;
  private final Map<String, Entry> entries;
  private final Map<String, Set<String>> members;
  private final Map<String, Set<String>> dependencies;
  private final Set<String> declaredNames;

  private ToolCatalog(List<ToolCallback> supplied, List<Entry> definitions) {
    var registered = new TreeMap<String, ToolCallback>();
    for (var callback : supplied) {
      var definition = callback.getToolDefinition();
      if (registered.putIfAbsent(definition.name(), frozen(callback, definition)) != null)
        throw new IllegalArgumentException("Duplicate tool: " + definition.name());
    }
    var metadata = new TreeMap<String, Entry>();
    definitions.forEach(
        e -> {
          if (metadata.putIfAbsent(e.name(), e) != null)
            throw new IllegalArgumentException("Duplicate catalog entry: " + e.name());
        });
    var declaredSets =
        definitions.stream().map(Entry::set).collect(java.util.stream.Collectors.toSet());
    for (var definition : definitions)
      for (String dependency : definition.dependencies())
        if (!declaredSets.contains(dependency))
          throw new IllegalArgumentException("Unknown operation set dependency: " + dependency);
    var present = new TreeMap<String, Entry>();
    var sets = new TreeMap<String, Set<String>>();
    var edges = new TreeMap<String, Set<String>>();
    for (String name : registered.keySet()) {
      Entry e = metadata.get(name);
      if (e == null) throw new IllegalArgumentException("Unclassified tool: " + name);
      present.put(name, e);
      if (!e.basic()) {
        sets.computeIfAbsent(e.set(), x -> new TreeSet<>()).add(name);
        edges.computeIfAbsent(e.set(), x -> new TreeSet<>()).addAll(e.dependencies());
      }
    }
    // A disabled dependency makes its dependent set unavailable, rather than partially loading it.
    boolean changed;
    do {
      changed = false;
      for (String id : List.copyOf(sets.keySet())) {
        boolean incomplete =
            definitions.stream()
                .anyMatch(
                    e -> e.set().equals(id) && !e.basic() && !registered.containsKey(e.name()));
        if (incomplete
            || edges.getOrDefault(id, Set.of()).stream().anyMatch(d -> !sets.containsKey(d))) {
          sets.remove(id);
          changed = true;
        }
      }
    } while (changed);
    this.callbacks = Collections.unmodifiableMap(registered);
    this.declaredNames = Set.copyOf(metadata.keySet());
    this.entries = Collections.unmodifiableMap(present);
    this.members = immutableSets(sets);
    this.dependencies = immutableSets(edges);
  }

  private static Map<String, Set<String>> immutableSets(Map<String, Set<String>> source) {
    var result = new TreeMap<String, Set<String>>();
    source.forEach((k, v) -> result.put(k, Set.copyOf(v)));
    return Collections.unmodifiableMap(result);
  }

  public static ToolCatalog standard(List<ToolCallback> callbacks) {
    try (var input = ToolCatalog.class.getResourceAsStream("/tool-discovery-catalog.json")) {
      if (input == null) throw new IllegalStateException("Tool catalog resource missing");
      var entries = new ObjectMapper().readValue(input, Entry[].class);
      return new ToolCatalog(callbacks, List.of(entries));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read tool catalog", e);
    }
  }

  static ToolCatalog of(List<ToolCallback> callbacks, List<Entry> entries) {
    return new ToolCatalog(callbacks, entries);
  }

  public Set<String> names() {
    return callbacks.keySet();
  }

  boolean isDeclaredName(String query) {
    String normalized =
        java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC).strip();
    return declaredNames.stream().anyMatch(name -> name.equalsIgnoreCase(normalized));
  }

  public Set<String> basicNames() {
    var names = new TreeSet<String>();
    entries.values().stream().filter(Entry::basic).forEach(e -> names.add(e.name()));
    return Set.copyOf(names);
  }

  public String operationSetFor(String name) {
    return entries.get(name).set();
  }

  public Collection<Entry> searchable() {
    return entries.values().stream()
        .filter(e -> e.basic() || members.containsKey(e.set()))
        .toList();
  }

  public ToolCallback callback(String name) {
    return Objects.requireNonNull(callbacks.get(name), "Unknown tool " + name);
  }

  public Set<String> closure(Set<String> requested) {
    var result = new TreeSet<String>();
    var queue = new ArrayDeque<>(requested);
    while (!queue.isEmpty()) {
      var id = queue.removeFirst();
      if (!members.containsKey(id))
        throw new IllegalArgumentException("Unavailable operation set: " + id);
      if (result.add(id)) queue.addAll(dependencies.getOrDefault(id, Set.of()));
    }
    return Set.copyOf(result);
  }

  public Set<String> toolsForSets(Set<String> ids) {
    var names = new TreeSet<String>();
    closure(ids).forEach(id -> names.addAll(members.get(id)));
    return Set.copyOf(names);
  }

  /** Freeze dynamic schema values, preserving callback authority and delivery metadata. */
  static ToolCallback frozen(ToolCallback source, ToolDefinition original) {
    var definition =
        ToolDefinition.builder()
            .name(original.name())
            .description(original.description())
            .inputSchema(original.inputSchema())
            .build();
    return DeliveryToolRegistration.preservePolicy(
        source,
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return definition;
          }

          public ToolMetadata getToolMetadata() {
            return source.getToolMetadata();
          }

          public String call(String input) {
            return source.call(input);
          }

          public String call(String input, ToolContext context) {
            return source.call(input, context);
          }
        });
  }
}
