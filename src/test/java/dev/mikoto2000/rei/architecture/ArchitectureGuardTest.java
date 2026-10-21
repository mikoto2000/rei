package dev.mikoto2000.rei.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.mikoto2000.rei.core.chat.AgentRunConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureGuardTest {
  @TempDir Path directory;

  @Test
  void budgetRejectsAdditionsAndMissingFilesAndRequiresExplicitShrink() throws Exception {
    Path file = directory.resolve("baseline.txt");
    assertThrows(
        AssertionError.class, () -> ArchitectureBaseline.check(file, new TreeSet<>(), true));
    Files.write(file, List.of("old"));
    assertThrows(
        AssertionError.class,
        () -> ArchitectureBaseline.check(file, new TreeSet<>(List.of("new", "old")), true));
    assertEquals(List.of("old"), Files.readAllLines(file));
    assertThrows(
        AssertionError.class, () -> ArchitectureBaseline.check(file, new TreeSet<>(), false));
    ArchitectureBaseline.check(file, new TreeSet<>(), true);
    assertTrue(Files.readAllLines(file).isEmpty());
    assertThrows(
        AssertionError.class,
        () -> ArchitectureBaseline.check(file, new TreeSet<>(List.of("old")), false));
  }

  private ArchitectureModel.Edge edge(String from, String to, boolean wiring) {
    return new ArchitectureModel.Edge(
        ArchitectureModel.ROOT + from + ".Source",
        ArchitectureModel.ROOT + to + ".Target",
        ArchitectureModel.ROOT + from,
        ArchitectureModel.ROOT + to,
        wiring,
        "fixture");
  }

  @Test
  void detectsUiDependenciesAndThreeFeatureCyclesAndSeparatesWiring() {
    var edges =
        List.of(
            edge("application.run", "ui.shell", false),
            edge("core.chat", "web", false),
            edge("goal", "activity", false),
            edge("activity", "voice", false),
            edge("voice", "goal", false),
            edge("core.chat", "ui.shell", true),
            edge("paper", "core.project", false));
    var actual = ArchitectureModel.violations(edges);
    assertEquals(1, actual.get("A-application-ui").size());
    assertEquals(2, actual.get("B-execution-ui").size());
    assertEquals(3, actual.get("C-business-cycles").size());
    assertEquals(1, actual.get("D-configuration").size());
    assertEquals(1, actual.get("E-core-concentration").size());
    assertTrue(
        ArchitectureModel.violations(List.of(edge("goal", "activity", false)))
            .get("C-business-cycles")
            .isEmpty());
  }

  @Test
  void bytecodeImportDetectsRealConfigurationToUiDependency() {
    var imported =
        new ClassFileImporter()
            .importClasses(
                AgentRunConfiguration.class,
                dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator.class);
    var edges = ArchitectureModel.edges(imported);
    assertTrue(edges.stream().anyMatch(e -> e.wiring() && ArchitectureModel.ui(e.targetPackage())));
    assertTrue(ArchitectureModel.violations(edges).get("B-execution-ui").isEmpty());
    assertFalse(ArchitectureModel.violations(edges).get("D-configuration").isEmpty());
  }

  @Test
  void newUiViolationInCompiledBytecodeIsRejected() throws Exception {
    Path ui = directory.resolve("View.java");
    Path run = directory.resolve("Probe.java");
    Files.writeString(ui, "package dev.mikoto2000.rei.ui.shell; public class View {}");
    Files.writeString(
        run,
        "package dev.mikoto2000.rei.application.run; public class Probe {"
            + " dev.mikoto2000.rei.ui.shell.View view; }");
    assertEquals(
        0,
        javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-proc:none",
                "-d",
                directory.toString(),
                ui.toString(),
                run.toString()));
    var actual =
        ArchitectureModel.violations(
            ArchitectureModel.edges(new ClassFileImporter().importPath(directory)));
    Path baseline = directory.resolve("empty.txt");
    Files.write(baseline, List.of());
    for (String rule : List.of("A-application-ui", "B-execution-ui")) {
      assertFalse(actual.get(rule).isEmpty());
      assertThrows(
          AssertionError.class,
          () -> ArchitectureBaseline.check(baseline, actual.get(rule), false));
    }
  }

  @Test
  void existingCycleCannotHideANewClassEdgeAndWiringDoesNotBecomeBusiness() throws Exception {
    var before =
        List.of(
            edge("goal", "activity", false),
            edge("activity", "voice", false),
            edge("voice", "goal", false));
    var added = new java.util.ArrayList<>(before);
    added.add(
        new ArchitectureModel.Edge(
            ArchitectureModel.ROOT + "goal.NewSource",
            ArchitectureModel.ROOT + "activity.Target",
            ArchitectureModel.ROOT + "goal",
            ArchitectureModel.ROOT + "activity",
            false,
            "new class edge in same SCC"));
    Path file = directory.resolve("cycle.txt");
    Files.write(file, ArchitectureModel.violations(before).get("C-business-cycles"));
    assertThrows(
        AssertionError.class,
        () ->
            ArchitectureBaseline.check(
                file, ArchitectureModel.violations(added).get("C-business-cycles"), false));
    var mixed =
        ArchitectureModel.violations(
            List.of(
                edge("goal", "activity", false),
                edge("activity", "voice", false),
                edge("voice", "goal", true)));
    assertTrue(mixed.get("C-business-cycles").isEmpty());
    assertEquals(3, mixed.get("C-wiring-cycles").size());
  }
}
