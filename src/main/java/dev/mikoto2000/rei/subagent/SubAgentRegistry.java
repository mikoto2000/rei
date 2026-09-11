package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.util.*;

/** A failed candidate never replaces the live snapshot. Runs retain their resolved definition. */
public final class SubAgentRegistry {
  private final Path directory;
  private final SubAgentDefinitionLoader loader;
  private volatile List<SubAgentDefinition> snapshot = List.of();
  public SubAgentRegistry(Path directory, SubAgentDefinitionLoader loader) {
    this.directory = directory.toAbsolutePath().normalize();
    this.loader = loader;
  }
  public Path directory() { return directory; }
  public List<SubAgentDefinition> list() { return snapshot; }
  public Optional<SubAgentDefinition> findById(String id) { return snapshot.stream().filter(d -> d.id().equals(id)).findFirst(); }
  public synchronized List<String> reload() {
    List<String> errors = new ArrayList<>();
    Map<String, SubAgentDefinition> candidate = new TreeMap<>();
    if (Files.notExists(directory)) { snapshot = List.of(); return List.of(); }
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList()) {
        try {
          var definition = loader.load(file);
          var previous = candidate.putIfAbsent(definition.id(), definition);
          if (previous != null) errors.add(file + ": id: duplicate id " + definition.id() + " (also " + previous.source() + ")");
        } catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
      }
    } catch (java.io.IOException e) { errors.add(directory + ": cannot scan configuration directory"); }
    if (errors.isEmpty()) snapshot = List.copyOf(candidate.values());
    return List.copyOf(errors);
  }
}
