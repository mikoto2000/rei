package dev.mikoto2000.rei.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.context.annotation.Configuration;

/** Bytecode dependencies; no Spring context, source import guessing or production changes. */
final class ArchitectureModel {
  static final String ROOT = "dev.mikoto2000.rei.";

  record Edge(
      String source,
      String target,
      String sourcePackage,
      String targetPackage,
      boolean wiring,
      String evidence) {
    String pair() {
      return source + " -> " + target;
    }
  }

  static boolean wiring(JavaClass type) {
    return type.isAnnotatedWith(Configuration.class)
        || type.isMetaAnnotatedWith(Configuration.class)
        || type.getEnclosingClass().map(ArchitectureModel::wiring).orElse(false);
  }

  static String feature(String pkg) {
    if (pkg.equals(ROOT.substring(0, ROOT.length() - 1))) return "(root)";
    String relative = pkg.substring(ROOT.length());
    String[] parts = relative.split("\\.");
    // core/application contain several distinct responsibilities, never one monolithic layer.
    return (parts[0].equals("core") || parts[0].equals("application")) && parts.length > 1
        ? parts[0] + "." + parts[1]
        : parts[0];
  }

  static List<Edge> edges(JavaClasses classes) {
    Set<String> imported = new HashSet<>();
    classes.forEach(c -> imported.add(c.getName()));
    List<Edge> edges = new ArrayList<>();
    for (JavaClass source : classes) {
      for (var dependency : source.getDirectDependenciesFromSelf()) {
        var target = dependency.getTargetClass();
        if (imported.contains(target.getName())
            && internal(source.getPackageName())
            && internal(target.getPackageName())) {
          edges.add(
              new Edge(
                  source.getName(),
                  target.getName(),
                  source.getPackageName(),
                  target.getPackageName(),
                  wiring(source),
                  dependency.getDescription()));
        }
      }
    }
    return edges;
  }

  static boolean internal(String pkg) {
    return pkg.equals(ROOT.substring(0, ROOT.length() - 1)) || pkg.startsWith(ROOT);
  }

  static boolean ui(String pkg) {
    return pkg.equals(ROOT + "web")
        || pkg.startsWith(ROOT + "web.")
        || pkg.equals(ROOT + "ui")
        || pkg.startsWith(ROOT + "ui.");
  }

  static boolean execution(String pkg) {
    return List.of(
            "application.run",
            "application.session",
            "core.chat",
            "core.stagnation",
            "core.execution",
            "core.checkpoint",
            "core.process")
        .stream()
        .anyMatch(p -> pkg.equals(ROOT + p) || pkg.startsWith(ROOT + p + "."));
  }

  static SortedMap<String, SortedSet<String>> violations(List<Edge> edges) {
    SortedMap<String, SortedSet<String>> result = new TreeMap<>();
    for (String rule :
        List.of(
            "A-application-ui",
            "B-execution-ui",
            "C-business-cycles",
            "C-wiring-cycles",
            "D-configuration",
            "E-core-concentration")) {
      result.put(rule, new TreeSet<>());
    }
    Map<String, Set<String>> business = graph(edges, false);
    Map<String, Set<String>> all = graph(edges, true);
    for (Edge edge : edges) {
      if (!edge.wiring
          && (edge.sourcePackage.equals(ROOT + "application")
              || edge.sourcePackage.startsWith(ROOT + "application."))
          && ui(edge.targetPackage)) result.get("A-application-ui").add(edge.pair());
      if (!edge.wiring && execution(edge.sourcePackage) && ui(edge.targetPackage))
        result.get("B-execution-ui").add(edge.pair());
      String from = feature(edge.sourcePackage), to = feature(edge.targetPackage);
      if (!from.equals(to)) {
        if (!edge.wiring && reachable(business, to, from))
          result.get("C-business-cycles").add(edge.pair());
        if (reachable(all, to, from) && (edge.wiring || !reachable(business, to, from)))
          result
              .get("C-wiring-cycles")
              .add((edge.wiring ? "wiring: " : "business: ") + edge.pair());
        if (edge.wiring) result.get("D-configuration").add(edge.pair());
      }
      if (!(edge.sourcePackage.equals(ROOT + "core")
              || edge.sourcePackage.startsWith(ROOT + "core."))
          && (edge.targetPackage.equals(ROOT + "core")
              || edge.targetPackage.startsWith(ROOT + "core.")))
        result
            .get("E-core-concentration")
            .add((edge.wiring ? "wiring: " : "business: ") + edge.pair());
    }
    return result;
  }

  static Map<String, Set<String>> graph(List<Edge> edges, boolean includeWiring) {
    Map<String, Set<String>> graph = new TreeMap<>();
    for (Edge edge : edges) {
      if (includeWiring || !edge.wiring)
        graph
            .computeIfAbsent(feature(edge.sourcePackage), ignored -> new TreeSet<>())
            .add(feature(edge.targetPackage));
    }
    return graph;
  }

  static boolean reachable(Map<String, Set<String>> graph, String from, String to) {
    Set<String> visited = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    pending.add(from);
    while (!pending.isEmpty()) {
      String next = pending.removeFirst();
      if (next.equals(to)) return true;
      if (visited.add(next)) pending.addAll(graph.getOrDefault(next, Set.of()));
    }
    return false;
  }
}
