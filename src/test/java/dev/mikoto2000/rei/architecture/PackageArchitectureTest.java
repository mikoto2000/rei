package dev.mikoto2000.rei.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageArchitectureTest {
  @Test
  void productionDependenciesMustNotGrow() throws Exception {
    // Import only compiled production output, never fixtures, tests or dependency jars.
    Path production =
        Path.of(
            dev.mikoto2000.rei.core.Tools.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    var classes = new ClassFileImporter().importPath(production);
    if (classes.size() < 100)
      throw new AssertionError("Production import unexpectedly empty/small");
    var edges = ArchitectureModel.edges(classes);
    var violations = ArchitectureModel.violations(edges);
    Path report = Path.of("target/architecture");
    Files.createDirectories(report);
    for (var entry : violations.entrySet())
      Files.write(
          report.resolve(entry.getKey() + ".txt"), entry.getValue(), StandardCharsets.UTF_8);
    List<String> dependencies = new ArrayList<>();
    dependencies.add("source\ttarget\tsourcePackage\ttargetPackage\tkind\tevidence");
    edges.stream()
        .map(
            e ->
                e.source()
                    + "\t"
                    + e.target()
                    + "\t"
                    + e.sourcePackage()
                    + "\t"
                    + e.targetPackage()
                    + "\t"
                    + (e.wiring() ? "wiring" : "business")
                    + "\t"
                    + e.evidence().replace('\t', ' ').replace('\n', ' '))
        .sorted()
        .distinct()
        .forEach(dependencies::add);
    Files.write(report.resolve("dependencies.tsv"), dependencies, StandardCharsets.UTF_8);
    List<String> types = new ArrayList<>(List.of("class\tpackage\twiring"));
    classes.stream()
        .map(c -> c.getName() + "\t" + c.getPackageName() + "\t" + ArchitectureModel.wiring(c))
        .sorted()
        .forEach(types::add);
    Files.write(report.resolve("classes.tsv"), types, StandardCharsets.UTF_8);
    boolean shrink = Boolean.getBoolean("architecture.baseline.shrink");
    if (shrink && "true".equalsIgnoreCase(System.getenv("CI")))
      throw new AssertionError("Baseline updates are forbidden in CI");
    // Validate all additions before allowing any shrink writes.
    List<Throwable> failures = new ArrayList<>();
    for (var entry : violations.entrySet()) {
      try {
        ArchitectureBaseline.check(
            Path.of("config/architecture", entry.getKey() + ".txt"), entry.getValue(), false);
      } catch (AssertionError failure) {
        failures.add(failure);
      }
    }
    if (shrink
        && failures.stream()
            .noneMatch(
                f ->
                    f.getMessage().contains("NEW dependencies")
                        || f.getMessage().contains("Missing baseline"))) {
      for (var entry : violations.entrySet())
        ArchitectureBaseline.check(
            Path.of("config/architecture", entry.getKey() + ".txt"), entry.getValue(), true);
    } else if (!failures.isEmpty()) {
      throw new AssertionError(
          String.join("\n", failures.stream().map(Throwable::getMessage).toList()));
    }
  }
}
